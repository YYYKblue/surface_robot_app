package cn.surface.operator

import android.os.Looper
import cn.surface.operator.connection.UnderwaterController
import cn.surface.operator.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Actual local WebSocket frames, with the GUI clock independent of the network callback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class UnderwaterConnectionTest {
    private class Session(private val validHello:Boolean=true,private val enforceGeneration:Boolean=false):AutoCloseable {
        val server=MockWebServer()
        val socket=AtomicReference<WebSocket>()
        val commands=CopyOnWriteArrayList<Pair<Int,JsonObject>>()
        val accepted=CopyOnWriteArrayList<JsonObject>()
        val rejected=CopyOnWriteArrayList<JsonObject>()
        val logs=CopyOnWriteArrayList<String>()
        private val generation=AtomicLong(7)
        private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val controller=UnderwaterController(scope,log={message,details->logs.add("$message $details")},clock={System.nanoTime()/1_000_000L})
        @Volatile private var link=0
        private var sequence=0L
        @Volatile var report=packet("connected" to true,"connection_id" to "serial-1","control_generation" to 7,
            "epoch" to 3,"state" to "READY","available" to true,"source" to "NANO","owner" to null,
            "armed" to true,"result" to "OK","reason" to "ready","last_stop_reason" to "",
            "report_age_sec" to 0.0,"control_fresh" to true,"motion_enabled" to true,
            "firmware_protocol_version" to 6,"direction" to "STOP","speed_percent" to 0,
            "stop_confirmed" to true)
        init {
            // A safety stop can correctly cancel a socket with a queued motion frame.
            // The service remains available for the controller's automatic reconnect.
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:RecordedRequest)=response(link)
            }
            server.start();connect()
        }
        val address get()=server.url("/").toString().replace("http:","ws:")
        private fun response(current:Int)=MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
                private var lastSequence=-1L
                override fun onMessage(webSocket:WebSocket,text:String) {
                    val p=wireJson.parseToJsonElement(text) as JsonObject
                    commands.add(current to p)
                    if(p.str("op")=="hello") {
                        webSocket.send(packet("type" to "hello","protocol" to if(validHello) "under-operator-v1" else "f1-operator-v1",
                            "device" to "h7_under_original_v6","session_id" to "service-$current","client_id" to "under-client-$current",
                            "capabilities" to JsonArray(listOf(JsonPrimitive("under_manual_v1"))),
                            "drive_output" to packet("min" to 0,"max" to 100,"step" to 1,"firmware_protocol_version" to 6)).toString())
                        socket.set(webSocket)
                    } else if(enforceGeneration && p.str("op")=="manual_stop") {
                        // Match upstream Session.stop: a manual_stop changes the control generation.
                        update("control_generation" to generation.incrementAndGet())
                    } else if(enforceGeneration && p.str("op")=="manual_input") {
                        val seq=p.long("seq",-1)
                        if(seq>lastSequence && p.long("control_generation")==generation.get()) accepted.add(p)
                        else rejected.add(p)
                        lastSequence=maxOf(lastSequence,seq)
                    }
                }
            })
        fun connect() {
            ++link
            sequence=0
            socket.set(null)
            controller.connect(address)
            controller.foreground(true) // ViewModel synchronization must not open a second handshaking socket.
            controller.visible(true)
        }
        @Synchronized fun update(vararg fields:Pair<String,Any?>) {report=JsonObject(report+packet(*fields))}
        fun send(fields:JsonObject=EMPTY) {
            socket.get()?.send(JsonObject(packet("type" to "telemetry","protocol" to "under-operator-v1",
                "device" to "h7_under_original_v6","session_id" to "service-$link","sequence" to ++sequence,
                "state" to report)+fields).toString())
        }
        fun pump(telemetry:Boolean=true) {
            if(telemetry) send()
            Thread.sleep(15)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        }
        fun until(description:String,telemetry:Boolean=true,condition:()->Boolean) {
            repeat(160) {pump(telemetry);if(condition()) return}
            fail("Timed out: $description; state=${controller.state.value}; requests=${server.requestCount}; " +
                "commands=${commands.takeLast(12)}; logs=${logs.takeLast(15)}")
        }
        fun ready()=until("water controller ready") {controller.state.value.canDrive}
        fun inputs(connection:Int?=null)=commands.filter {connection==null || it.first==connection}
            .map {it.second}.filter {it.str("op")=="manual_input" && it.str("direction")!="STOP" && it.long("speed_percent")>0}
        fun capture(name:String="underwater-wire") {
            File("build/reports/contracts/$name.json").apply {
                parentFile?.mkdirs();writeText(JsonArray(commands.map {it.second}).toString())
            }
        }
        override fun close() {controller.close();scope.cancel();server.shutdown()}
    }

    @Test fun independentProtocolFramesCarryCurrentGenerationAndOrderedSequence() {
        Session().use {s ->
            s.ready();s.controller.speed(37);s.controller.direction("FORWARD")
            s.until("three input frames") {s.inputs().size>=3}
            val inputs=s.inputs()
            assertTrue(inputs.all {it.long("control_generation")==7L && it.long("speed_percent")==37L})
            assertTrue(inputs.zipWithNext().all {(a,b)->a.long("seq")<b.long("seq")})
            assertTrue(s.commands.all {(_,p)->p.str("protocol")=="under-operator-v1" && p.str("device")=="h7_under_original_v6"})
            val hello=s.commands.single {it.second.str("op")=="hello"}.second
            assertEquals(1L,hello.long("version"))
            s.controller.stop("operator")
            s.until("explicit water stop") {s.commands.any {it.second.str("op")=="manual_stop" && it.second.str("reason")=="operator"}}
            val count=s.inputs().size
            repeat(8) {s.pump()}
            assertEquals(count,s.inputs().size)
            assertEquals("STOP",s.controller.state.value.direction)
            s.capture()
        }
    }

    @Test fun remoteTakeoverAndReturnRequireNewPressWithNewGeneration() {
        Session().use {s ->
            s.ready();s.controller.direction("LEFT")
            s.until("initial left") {s.inputs().isNotEmpty()}
            s.update("source" to "RC","state" to "RC","available" to false,"result" to "RC_OK","control_generation" to 8)
            s.until("RC revokes water intent") {!s.controller.state.value.canDrive && s.controller.state.value.direction=="STOP"}
            val count=s.inputs().size
            s.update("source" to "NANO","state" to "READY","available" to true,"result" to "OK","control_generation" to 9)
            s.ready();repeat(8) {s.pump()}
            assertEquals(count,s.inputs().size)
            s.controller.direction("RIGHT")
            s.until("new right after return") {s.inputs().any {it.str("direction")=="RIGHT" && it.long("control_generation")==9L}}
            assertTrue(s.inputs().drop(count).all {it.str("direction")=="RIGHT" && it.long("control_generation")==9L})
        }
    }

    @Test fun zeroOutputThenIncreaseAndReconnectNeverRestoreOldDirection() {
        Session().use {s ->
            s.ready();s.controller.direction("FORWARD")
            s.until("initial forward") {s.inputs().isNotEmpty()}
            s.controller.speed(0)
            assertEquals("STOP",s.controller.state.value.direction)
            val count=s.inputs().size
            s.controller.speed(75);repeat(8) {s.pump()}
            assertEquals(count,s.inputs().size)
            s.controller.direction("REVERSE")
            s.until("new reverse") {s.inputs().any {it.str("direction")=="REVERSE"}}
            s.controller.disconnect();s.connect();s.ready()
            repeat(8) {s.pump()}
            assertTrue(s.inputs(2).isEmpty())
            assertEquals("STOP",s.controller.state.value.direction)
            s.controller.direction("LEFT")
            s.until("explicit press on new connection") {s.inputs(2).any {it.str("direction")=="LEFT"}}
        }
    }

    @Test fun normalReleaseOrdersNeutralBeforeRapidRepressWithoutChangingGeneration() {
        Session(enforceGeneration=true).use {s ->
            s.ready();s.controller.direction("FORWARD")
            s.until("accepted initial forward") {s.accepted.any {it.str("direction")=="FORWARD"}}
            s.controller.direction("STOP")
            s.controller.direction("LEFT") // No telemetry round trip between releasing and pressing again.
            s.until("accepted left after ordered neutral") {s.accepted.any {it.str("direction")=="LEFT"}}
            val firstNeutral=s.accepted.indexOfFirst {it.str("direction")=="STOP" && it.long("speed_percent")==0L}
            val left=s.accepted.indexOfFirst {it.str("direction")=="LEFT"}
            assertTrue("normal release must reach peer before the new motion",firstNeutral>=0 && firstNeutral<left)
            assertTrue(s.commands.none {it.second.str("op")=="manual_stop"})
            assertTrue(s.rejected.isEmpty())
            assertTrue(s.accepted.all {it.long("control_generation")==7L})

            val neutrals=s.accepted.count {it.str("direction")=="STOP"}
            s.controller.speed(0)
            s.controller.stop("under_control_changed") // Disabled Compose gesture cleanup after the normal neutral release.
            s.controller.speed(75)
            assertEquals("STOP",s.controller.state.value.direction)
            s.until("zero output emits neutral") {s.accepted.count {it.str("direction")=="STOP"}>neutrals}
            val moving=s.accepted.count {it.str("direction")!="STOP"}
            repeat(5) {s.pump()}
            assertEquals("raising speed alone must not restore the old direction",moving,s.accepted.count {it.str("direction")!="STOP"})
            s.controller.direction("REVERSE")
            s.until("explicit reverse after zero") {s.accepted.any {it.str("direction")=="REVERSE" && it.long("speed_percent")==75L}}
            assertTrue(s.commands.none {it.second.str("op")=="manual_stop"})
            assertTrue(s.rejected.isEmpty())
            assertTrue(s.accepted.all {it.long("control_generation")==7L})
            assertTrue(s.accepted.zipWithNext().all {(a,b)->a.long("seq")<b.long("seq")})
            s.capture("underwater-release-wire")
        }
    }

    @Test fun queuedH7FeedbackCountsTimeBeforeTheGuiProcessesIt() {
        Session().use {s ->
            s.ready()
            // The network callback has already received this state, but Main is stalled.
            // A decoder-time timestamp would incorrectly treat this .12-second-old report as fresh.
            s.update("report_age_sec" to .12)
            s.send();Thread.sleep(180)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            assertFalse(s.controller.state.value.canDrive)
            s.controller.direction("FORWARD")
            repeat(3) {s.pump(telemetry=false)}
            assertTrue(s.inputs().isEmpty())
            s.update("report_age_sec" to 0.0);s.ready()
            repeat(5) {s.pump()}
            assertTrue(s.inputs().isEmpty())
            s.controller.direction("FORWARD")
            s.until("fresh explicit direction") {s.inputs().isNotEmpty()}
        }
    }

    @Test fun ownerLossAndLifecycleClearOnlySavedIntentUntilANewPress() {
        Session().use {s ->
            s.ready();s.controller.direction("RIGHT")
            s.until("initial right") {s.inputs().isNotEmpty()}
            s.update("owner" to "other-client")
            s.until("other operator blocks") {!s.controller.state.value.canDrive && s.controller.state.value.direction=="STOP"}
            s.update("owner" to null);s.ready()
            val count=s.inputs().size
            repeat(4) {s.pump()};assertEquals(count,s.inputs().size)
            s.controller.foreground(false);s.controller.direction("LEFT")
            repeat(3) {s.pump()};assertEquals(count,s.inputs().size)
            s.controller.foreground(true);s.ready()
            repeat(3) {s.pump()};assertEquals(count,s.inputs().size)
            s.controller.direction("LEFT")
            s.until("new left") {s.inputs().size>count}
            s.controller.visible(false)
            val hidden=s.inputs().size
            repeat(4) {s.pump()};assertEquals(hidden,s.inputs().size)
            assertEquals("STOP",s.controller.state.value.direction)
        }
    }

    @Test fun conflatedTelemetryCannotHideATransientLossOfPermission() {
        Session().use {s ->
            s.ready();s.controller.direction("FORWARD")
            s.until("initial forward") {s.inputs().isNotEmpty()}
            // Both frames reach the network callback while Main is not dispatched.
            // A bounded inbox may keep just the latest display state, but must retain revocation.
            s.update("available" to false);s.send();Thread.sleep(25)
            s.update("available" to true);s.send();Thread.sleep(25)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            assertEquals("STOP",s.controller.state.value.direction)
            s.until("revocation reached peer") {s.commands.any {it.second.str("op")=="manual_stop"}}
            val count=s.inputs().size
            s.ready();repeat(5) {s.pump()}
            assertEquals(count,s.inputs().size)
        }
    }

    @Test fun repeatedLifecycleRevocationSendsOneStopAndIdleObservationDoesNotClaimControl() {
        Session().use {s ->
            s.ready()
            s.controller.foreground(false);s.controller.visible(false);s.controller.stop("touch_cancelled")
            repeat(3) {s.pump()}
            assertTrue(s.commands.none {it.second.str("op")=="manual_stop"})
            s.controller.foreground(true);s.controller.visible(true);s.ready()
            s.controller.direction("FORWARD")
            s.until("initial forward") {s.inputs().isNotEmpty()}
            s.controller.foreground(false);s.controller.visible(false);s.controller.stop("touch_cancelled")
            s.until("one lifecycle stop") {s.commands.count {it.second.str("op")=="manual_stop"}==1}
            repeat(4) {s.pump()}
            assertEquals(1,s.commands.count {it.second.str("op")=="manual_stop"})
            assertTrue(s.controller.state.value.connected)
            s.controller.stop("operator")
            s.until("idle explicit stop remains available") {s.commands.count {it.second.str("op")=="manual_stop"}==2}
            assertTrue(s.controller.state.value.connected)
        }
    }

    @Test fun wrongProtocolCannotEnableTheWaterController() {
        Session(validHello=false).use {s ->
            s.until("client hello sent",telemetry=false) {s.commands.any {it.second.str("op")=="hello"}}
            repeat(8) {s.pump()}
            assertFalse(s.controller.state.value.canDrive)
            s.controller.direction("FORWARD")
            repeat(5) {s.pump()}
            assertTrue(s.inputs().isEmpty())
        }
    }
}
