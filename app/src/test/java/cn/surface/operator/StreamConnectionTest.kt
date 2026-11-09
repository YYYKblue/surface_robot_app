package cn.surface.operator

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import cn.surface.operator.core.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1280dp-h800dp-land-xhdpi")
class StreamConnectionTest {
    @Test fun actualGatewayFixtureSupportsSplitStreamsBurstAndStaleQueueAge() {
        val fixture=(wireJson.parseToJsonElement(javaClass.getResource("/gateway-stream.json")!!.readText()) as JsonArray)
            .map {it as JsonObject}
        val server=MockWebServer();val socket=AtomicReference<WebSocket>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
            override fun onMessage(webSocket:WebSocket,text:String) {
                if((wireJson.parseToJsonElement(text) as JsonObject).str("op")=="hello") {
                    socket.set(webSocket);fixture.forEach {webSocket.send(it.toString())}
                }
            }
        }));server.start()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val vm=ViewModelProvider(controller.get())[OperatorViewModel::class.java]
        fun pump() {Thread.sleep(15);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))}
        fun until(description:String,condition:()->Boolean) {
            repeat(200) {pump();if(condition()) return};fail("Timed out: $description")
        }
        fun telemetry(sequence:Long,queue:Double=0.0)=JsonObject(fixture.last()+packet("stream" to
            packet("session" to "fixture-stream","sequence" to sequence,"server_queue_ms" to queue)))
        try {
            vm.connect(server.url("/").toString().replace("http:","ws:"))
            until("compressed map, history and separate diagnostics") {
                vm.state.value.map?.revision==7L && vm.state.value.task.obj("actual_track").long("point_count")==4L &&
                    vm.state.value.logs.any {it.eventId==1L && it.reason=="input_timeout"}
            }
            val initial=vm.state.value
            assertEquals(2,initial.task.obj("actual_track").arr("segments").size)
            assertEquals(35L,initial.stream.lastTelemetryReceivedAt-initial.receivedAt)
            assertEquals("runtime-a",initial.mapSession)
            for(i in 2L..1000L) socket.get().send(telemetry(i).toString())
            socket.get().send(packet("type" to "reply","request_id" to "retained","success" to false,"message" to "洪峰期间保留回复").toString())
            until("latest telemetry and reliable reply") {
                vm.state.value.stream.sequence==1000L && vm.state.value.logs.any {it.reason=="洪峰期间保留回复"}
            }
            assertTrue(vm.state.value.connected)
            assertEquals(4L,vm.state.value.task.obj("actual_track").long("point_count"))
            socket.get().send(telemetry(1001,900.0).toString())
            until("server queue age expires control") {vm.state.value.stream.sequence==1001L}
            assertFalse(vm.state.value.fresh)
            val received=vm.state.value.receivedAt
            socket.get().send(fixture[3].toString());socket.get().send(fixture[2].toString())
            repeat(10) {pump()}
            assertEquals(received,vm.state.value.receivedAt)
            assertEquals(1,vm.state.value.logs.count {it.eventId==1L && it.reason=="input_timeout"})
            socket.get().send(telemetry(999).toString());repeat(5) {pump()}
            assertEquals(1001L,vm.state.value.stream.sequence)
        } finally {vm.disconnect();controller.pause().stop().destroy();server.shutdown()}
    }
}
