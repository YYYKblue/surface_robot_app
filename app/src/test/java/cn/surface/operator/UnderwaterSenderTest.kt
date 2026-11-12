package cn.surface.operator

import cn.surface.operator.connection.*
import cn.surface.operator.core.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class UnderwaterSenderTest {
    private class Fake:GatewayConnection {
        val sent=mutableListOf<JsonObject>()
        var accept=true
        var beforeSend:((JsonObject)->Unit)?=null
        override fun connect() {}
        override fun close() {}
        override fun send(value:JsonObject):Boolean {beforeSend?.invoke(value);sent.add(value);return accept}
        fun motions()=sent.filter {it.str("op")=="manual_input"}
        fun stops()=sent.filter {it.str("op")=="manual_stop"}
    }
    private class Fixture:AutoCloseable {
        var now=1000L
        val connection=Fake()
        val stopped=mutableListOf<Pair<Int,String>>()
        val sender=UnderwaterSender({now},{epoch,reason->stopped.add(epoch to reason)},false)
        fun arm() {sender.bind(1,connection);assertTrue(sender.activate(1));assertTrue(sender.arm(1))}
        fun update(deadline:Double=now+250.0,direction:String="FORWARD")=sender.update(1,UnderwaterIntent(direction,25,7),deadline)
        override fun close()=sender.close()
    }
    @Test fun handshakeAndNewGestureRequiredNoIdleFrames()=Fixture().use {f->
        assertFalse(f.sender.arm(1));f.sender.bind(1,f.connection)
        assertFalse(f.sender.arm(1));assertFalse(f.update())
        assertTrue(f.sender.activate(1));assertFalse(f.update());f.sender.pump()
        assertTrue(f.connection.sent.isEmpty());assertTrue(f.sender.arm(1));assertTrue(f.update());f.sender.pump()
        assertEquals(1,f.connection.motions().size)
    }
    @Test fun backgroundClockSends50msCadenceAndSkipsMissedTicks()=Fixture().use {f->
        f.arm();assertTrue(f.update());f.sender.pump()
        f.now+=49;f.sender.pump();assertEquals(1,f.connection.motions().size)
        f.now++;f.sender.pump();assertEquals(2,f.connection.motions().size)
        f.now+=75;f.sender.pump();f.sender.pump();assertEquals(3,f.connection.motions().size)
        assertEquals(listOf(1L,2L,3L),f.connection.motions().map {it.long("seq")})
    }
    @Test fun GuiStallExpiresAt250AndLateRefreshCannotRevive()=Fixture().use {f->
        f.arm();assertTrue(f.update(deadline=2000.0));f.sender.pump()
        f.now+=250
        assertFalse(f.update(deadline=2000.0));assertTrue(f.sender.isExpired)
        assertEquals("gui_input_expired",f.sender.stopReason);assertEquals(1,f.connection.stops().size)
        assertFalse(f.update());f.sender.pump();assertEquals(1,f.connection.motions().size)
        assertTrue(f.sender.arm(1));assertTrue(f.update());f.sender.pump()
        assertEquals(2,f.connection.motions().size)
    }
    @Test fun cumulativeFeedbackDeadlineCannotBeRenewedByGui()=Fixture().use {f->
        f.arm();assertTrue(f.update(deadline=1125.0));f.sender.pump()
        f.now=1100;assertTrue(f.update(deadline=1125.0));f.sender.pump()
        f.now=1125;f.sender.pump()
        assertEquals("h7_feedback_expired",f.sender.stopReason)
        assertFalse(f.update(deadline=1500.0));assertEquals(1,f.connection.stops().size)
    }
    @Test fun freshFeedbackBeforeDeadlineRenewsFeedbackButNotPastGuiExpiry()=Fixture().use {f->
        f.arm();assertTrue(f.update(deadline=1125.0));f.sender.pump()
        f.now=1100;assertTrue(f.update(deadline=1350.0));f.sender.pump()
        f.now=1200;assertTrue(f.update(deadline=1450.0));f.sender.pump()
        assertFalse(f.sender.isExpired)
        f.now=1450;f.sender.pump();assertTrue(f.sender.isExpired)
    }
    @Test fun clearAndReconnectNeverReplayOldMotion()=Fixture().use {f->
        f.arm();assertTrue(f.update());f.sender.pump();f.sender.clear("released")
        f.now+=50;f.sender.pump();assertEquals(1,f.connection.motions().size)
        f.sender.bind(2,f.connection);assertTrue(f.sender.activate(2));f.sender.pump()
        assertFalse(f.update());assertEquals(1,f.connection.motions().size)
        assertEquals(UNDERWATER_PROTOCOL,f.connection.stops().single().str("protocol"))
    }
    @Test fun sendFailureLatchesAndReentrantDisconnectDoesNotOverwriteNewEpoch()=Fixture().use {f->
        f.arm();assertTrue(f.update());f.connection.accept=false;f.sender.pump()
        assertTrue(f.sender.isExpired);assertEquals("send_not_ready",f.sender.stopReason)
        f.connection.accept=true;f.arm();assertTrue(f.update())
        f.connection.beforeSend={p->if(p.str("op")=="manual_input") f.sender.bind(2,null)}
        f.sender.pump();assertFalse(f.sender.isArmed);assertFalse(f.sender.isExpired)
    }
    @Test fun quickReleaseAndRepressKeepOneNeutralBeforeNewMotionOnThe50msCadence()=Fixture().use {f->
        f.arm();assertTrue(f.update(deadline=2000.0));f.sender.pump()
        f.now=1010;assertTrue(f.sender.release(1,7));f.sender.pump()
        assertFalse(f.sender.isArmed);assertTrue(f.sender.hasPending)
        assertTrue(f.sender.arm(1));assertTrue(f.update(deadline=2000.0,direction="LEFT"))
        f.now=1049;f.sender.pump();assertEquals(1,f.connection.motions().size)
        f.now=1050;f.sender.pump()
        assertEquals(listOf("FORWARD","STOP"),f.connection.motions().map {it.str("direction")})
        assertEquals(0L,f.connection.motions().last().long("speed_percent"))
        f.now=1099;f.sender.pump();assertEquals(2,f.connection.motions().size)
        f.now=1100;f.sender.pump()
        assertEquals(listOf("FORWARD","STOP","LEFT"),f.connection.motions().map {it.str("direction")})
        assertEquals(listOf(1L,2L,3L),f.connection.motions().map {it.long("seq")})
        assertTrue(f.connection.motions().all {it.long("control_generation")==7L})
        assertTrue(f.connection.stops().isEmpty())
    }
    @Test fun releaseSendsExactlyOneNeutralAndIdleDoesNotContinueSending()=Fixture().use {f->
        f.arm();assertTrue(f.update());f.sender.pump()
        f.now=1010;assertTrue(f.sender.release(1,7))
        f.now=1050;f.sender.pump();assertFalse(f.sender.hasPending)
        f.now=2000;f.sender.pump();assertTrue(f.sender.release(1,7));f.sender.pump()
        assertEquals(listOf("FORWARD","STOP"),f.connection.motions().map {it.str("direction")})
        assertTrue(f.connection.stops().isEmpty());assertFalse(f.sender.isExpired)
    }
    @Test fun aNewPressAfterNeutralStillWaitsForItsFollowingWireSlot()=Fixture().use {f->
        f.arm();assertTrue(f.update(deadline=2000.0));f.sender.pump()
        f.now=1010;assertTrue(f.sender.release(1,7))
        f.now=1050;f.sender.pump()
        f.now=1060;assertTrue(f.sender.arm(1));assertTrue(f.update(deadline=2000.0,direction="RIGHT"));f.sender.pump()
        assertEquals(2,f.connection.motions().size)
        f.now=1100;f.sender.pump();assertEquals("RIGHT",f.connection.motions().last().str("direction"))
    }
    @Test fun lifecycleStopSupersedesAnUnsentNeutralAndAnyNewQueuedGesture()=Fixture().use {f->
        f.arm();assertTrue(f.update());f.sender.pump()
        f.now=1010;assertTrue(f.sender.release(1,7))
        assertTrue(f.sender.arm(1));assertTrue(f.update(direction="REVERSE"))
        assertTrue(f.sender.clear("window_focus_lost"))
        f.now=1100;f.sender.pump()
        assertEquals(1,f.connection.motions().size);assertEquals(1,f.connection.stops().size)
        assertFalse(f.sender.hasPending)
    }
    @Test fun lateReleaseCannotConvertAnExpiredHoldIntoANewNeutralLease()=Fixture().use {f->
        f.arm();assertTrue(f.update(deadline=1125.0));f.sender.pump()
        f.now=1125;assertFalse(f.sender.release(1,7));f.sender.pump()
        assertEquals(1,f.connection.motions().size);assertEquals(1,f.connection.stops().size)
        assertEquals("h7_feedback_expired",f.sender.stopReason)
    }
}
