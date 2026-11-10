package cn.surface.operator

import cn.surface.operator.connection.GatewayConnection
import cn.surface.operator.connection.ManualSender
import cn.surface.operator.core.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ManualSenderTest {
    private class FakeConnection:GatewayConnection {
        val sent=mutableListOf<JsonObject>()
        var accept=true
        var beforeSend:((JsonObject)->Unit)?=null
        override fun connect() {}
        override fun close() {}
        override fun send(value:JsonObject):Boolean {
            beforeSend?.invoke(value)
            sent.add(value)
            return accept
        }
        fun motion()=sent.filter {it.str("op")=="manual_input"}
        fun stops()=sent.filter {it.str("op")=="manual_stop"}
    }
    private class Fixture:AutoCloseable {
        var now=1000L
        val connection=FakeConnection()
        val stopped=mutableListOf<Pair<Int,String>>()
        val sender=ManualSender({now},{epoch,reason->stopped.add(epoch to reason)},false)
        fun arm() {sender.bind(1,connection);assertTrue(sender.activate(1));assertTrue(sender.arm(1))}
        fun update(value:ManualIntent=ManualIntent("FORWARD"),receivedAt:Long=now)=sender.update(1,value,receivedAt)
        override fun close() {sender.close()}
    }

    @Test fun currentHandshakeAndExplicitArmAreRequired()=Fixture().use {f ->
        assertFalse(f.sender.arm(1))
        f.sender.bind(1,f.connection)
        assertFalse(f.sender.arm(1));assertFalse(f.update())
        assertFalse(f.sender.activate(0));assertTrue(f.sender.activate(1))
        assertFalse(f.update());assertTrue(f.sender.arm(1))
        assertTrue(f.update());f.sender.pump()
        assertEquals(1,f.connection.motion().size)
    }

    @Test fun cadenceIsIndependentOfGuiUpdatesAndNeverCatchesUpWithABurst()=Fixture().use {f ->
        f.arm();assertTrue(f.update());f.sender.pump()
        f.now+=49;f.sender.pump();assertEquals(1,f.connection.motion().size)
        f.now++;f.sender.pump();assertEquals(2,f.connection.motion().size)
        f.now+=75;assertTrue(f.update());f.sender.pump()
        assertEquals(3,f.connection.motion().size)
        f.sender.pump();assertEquals(3,f.connection.motion().size)
        f.now+=25;assertTrue(f.update(ManualIntent("LEFT")));f.sender.pump()
        assertEquals("LEFT",f.connection.motion().last().str("direction"))
        assertEquals(listOf(1L,2L,3L,4L),f.connection.motion().map {it.long("seq")})
    }

    @Test fun lateGuiUpdateCannotRefreshExpiredIntentAndClearDoesNotAcknowledgeExpiry()=Fixture().use {f ->
        f.arm();assertTrue(f.update());f.sender.pump()
        f.now+=250
        assertFalse(f.update()) // detects expiration even before a scheduled pump
        assertTrue(f.sender.expired);assertFalse(f.sender.armed)
        assertEquals("gui_input_expired",f.sender.stopReason)
        assertEquals(listOf(1 to "gui_input_expired"),f.stopped)
        assertEquals(1,f.connection.stops().size)
        f.sender.clear("gui_cleared",false);f.sender.pump()
        assertTrue(f.sender.expired);assertFalse(f.update())
        assertEquals(1,f.connection.stops().size)
        assertTrue(f.sender.arm(1));assertFalse(f.sender.expired)
        assertTrue(f.update(ManualIntent("REVERSE")));f.sender.pump()
        assertEquals("REVERSE",f.connection.motion().last().str("direction"))
    }

    @Test fun freshGuiIntentCannotExtendTelemetryPastIts800msDeadline()=Fixture().use {f ->
        f.arm();val receipt=f.now
        assertTrue(f.update(receivedAt=receipt));f.sender.pump()
        for(age in listOf(200L,400L,600L,799L)) {
            f.now=receipt+age
            assertTrue(f.update(receivedAt=receipt));f.sender.pump()
        }
        f.now=receipt+800;f.sender.pump()
        assertEquals("telemetry_expired",f.sender.stopReason)
        assertEquals(listOf(1 to "telemetry_expired"),f.stopped)
        assertFalse(f.update(receivedAt=f.now))
        assertEquals(1,f.connection.stops().size)
    }

    @Test fun idleDoesNotSeizeControlAndActiveToIdleSendsExactlyOneStopFrame()=Fixture().use {f ->
        f.arm();assertTrue(f.update(ManualIntent()));f.sender.pump()
        assertTrue(f.connection.sent.isEmpty())
        assertTrue(f.update(ManualIntent("FORWARD",brush=true)));f.sender.pump()
        f.now+=10;assertTrue(f.update(ManualIntent(brush=true)));f.sender.pump()
        assertTrue(f.connection.motion().last().flag("brush_enabled"))
        assertEquals("STOP",f.connection.motion().last().str("direction"))
        f.now+=10;assertTrue(f.update(ManualIntent()));f.sender.pump()
        val count=f.connection.motion().size
        f.now+=50;assertTrue(f.update(ManualIntent()));f.sender.pump()
        assertEquals(count,f.connection.motion().size)
        assertFalse(f.connection.motion().last().flag("brush_enabled"))
        assertTrue(f.connection.stops().isEmpty())
    }

    @Test fun resetAndOldEpochCallbacksCannotReplayOrActivatePreviousIntent()=Fixture().use {f ->
        f.arm();assertTrue(f.update());f.sender.pump()
        val next=FakeConnection()
        f.sender.bind(2,next)
        f.sender.bind(1,f.connection)
        assertEquals(2,f.sender.epoch)
        assertFalse(f.sender.activate(1));assertFalse(f.sender.arm(1));assertFalse(f.update())
        f.sender.pump();assertTrue(next.sent.isEmpty())
        assertTrue(f.sender.activate(2));assertTrue(f.sender.arm(2));f.sender.pump()
        assertTrue(next.sent.isEmpty())
        assertTrue(f.sender.update(2,ManualIntent("LEFT"),f.now));f.sender.pump()
        assertEquals("LEFT",next.motion().single().str("direction"))
        f.sender.reset(3);f.sender.pump()
        assertFalse(f.sender.activate(3));assertFalse(f.sender.armed)
    }

    @Test fun failedSendLatchesOnceAndStoppedCallbackDoesNotHoldTheSenderLock() {
        var now=1000L
        val connection=FakeConnection().apply {accept=false}
        val reports=mutableListOf<String>()
        lateinit var sender:ManualSender
        sender=ManualSender({now},{_,reason ->
            // Another thread must be able to inspect/clear the sender during callback.
            val read=CountDownLatch(1)
            val reader=Thread {sender.clear("callback",false);read.countDown()}.apply {start()}
            check(read.await(2,TimeUnit.SECONDS)) {"onStopped held sender lock"}
            reader.join()
            reports.add(reason)
        },false)
        try {
            sender.bind(1,connection);sender.activate(1);sender.arm(1)
            assertTrue(sender.update(1,ManualIntent("FORWARD"),now));sender.pump()
            assertEquals(listOf("send_not_ready"),reports)
            assertTrue(sender.expired);assertFalse(sender.armed)
            now+=50;sender.pump();assertFalse(sender.update(1,ManualIntent("FORWARD"),now))
            assertEquals(1,connection.motion().size);assertEquals(1,connection.stops().size)
        } finally {sender.close()}
    }

    @Test fun synchronousConnectionResetDuringSendCannotExpireOrModifyNewBinding()=Fixture().use {f ->
        f.arm();val next=FakeConnection()
        f.connection.beforeSend={
            f.sender.bind(2,next)
            f.sender.activate(2)
        }
        f.connection.accept=false
        assertTrue(f.update());f.sender.pump()
        assertEquals(2,f.sender.epoch);assertFalse(f.sender.expired);assertFalse(f.sender.armed)
        assertTrue(f.stopped.isEmpty());assertTrue(next.sent.isEmpty())
        assertTrue(f.sender.arm(2));assertTrue(f.sender.update(2,ManualIntent("LEFT"),f.now));f.sender.pump()
        assertEquals(1,next.motion().size)
    }
}
