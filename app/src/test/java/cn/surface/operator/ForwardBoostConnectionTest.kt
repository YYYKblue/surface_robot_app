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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1280dp-h800dp-land-xhdpi")
class ForwardBoostConnectionTest {
    @Test fun realSocketSettingFailureTimeoutConfirmationAndReconnectNeverReplay() {
        val server=MockWebServer()
        val socket=AtomicReference<WebSocket>()
        val commands=CopyOnWriteArrayList<JsonObject>()
        fun response()=MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
            override fun onMessage(webSocket:WebSocket,text:String) {
                val p=wireJson.parseToJsonElement(text) as JsonObject
                if(p.str("op")=="hello") {
                    socket.set(webSocket)
                    webSocket.send(packet("type" to "hello","protocol" to "f1-operator-v1","client_id" to "me",
                        "capabilities" to JsonArray(boostState().capabilities.map(::JsonPrimitive))).toString())
                } else if(p.str("op")=="task") commands.add(p)
            }
        })
        server.enqueue(response());server.enqueue(response());server.start()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val vm=ViewModelProvider(controller.get())[OperatorViewModel::class.java]
        var telemetry=boostState("rectangle").telemetry
        fun pump() {
            socket.get()?.send(JsonObject(telemetry+packet("type" to "telemetry")).toString())
            Thread.sleep(15)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        fun until(description:String,condition:()->Boolean) {
            repeat(150) {pump();if(condition()) return}
            fail("Timed out: $description")
        }
        fun reply(request:JsonObject,success:Boolean,pending:Boolean=false) {
            socket.get().send(packet("type" to "reply","op" to "task","action" to "set_forward_boost",
                "request_id" to request.str("request_id"),"success" to success,"pending" to pending,
                "message" to if(pending) "操作超时，结果尚未确认" else if(success) "设置已确认" else "控制器拒绝设置").toString())
        }
        try {
            vm.connect(server.url("/").toString().replace("http:","ws:"))
            until("ready") {vm.state.value.canSetForwardBoost}
            vm.setForwardBoost(true)
            assertEquals(false,vm.state.value.forwardBoostEnabled)
            assertFalse(vm.state.value.canStart)
            vm.setForwardBoost(true);vm.task("start")
            until("first command") {commands.size==1}
            val first=commands[0]
            assertEquals("rectangle",first.str("task_type"));assertEquals(JsonPrimitive(true),first["enabled"])
            assertTrue(first.str("request_id").isNotBlank())
            reply(first,false)
            until("rejection") {vm.state.value.pending.isEmpty() && vm.state.value.canSetForwardBoost}
            assertEquals(false,vm.state.value.forwardBoostEnabled)
            assertTrue(vm.state.value.logs.any {it.reason=="控制器拒绝设置"})

            vm.setForwardBoost(true)
            until("second command") {commands.size==2}
            val second=commands[1]
            assertNotEquals(first.str("request_id"),second.str("request_id"))
            reply(second,false,pending=true)
            telemetry=boostState("rectangle",enabled=true).telemetry
            until("matching telemetry") {vm.state.value.forwardBoostEnabled==true}
            assertEquals(1,vm.state.value.pending.size) // A matching boolean is not a final acknowledgement.
            assertFalse(vm.state.value.canStart)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            until("fresh after timeout") {vm.state.value.fresh}
            assertTrue(vm.state.value.forwardBoostStatus.contains("结果待确认"))
            assertEquals(2,commands.size)

            val oldSocket=socket.get()
            oldSocket.close(1000,"test reconnect")
            until("reconnected with robot setting") {socket.get()!==oldSocket && vm.state.value.forwardBoostEnabled==true}
            assertEquals(2,commands.size);assertEquals(1,vm.state.value.pending.size)
            vm.clearUncertain() // Explicit acknowledgement after inspecting the reconnected idle robot.
            assertTrue(vm.state.value.canSetForwardBoost)
            vm.setForwardBoost(false)
            until("third command") {commands.size==3}
            assertEquals(JsonPrimitive(false),commands[2]["enabled"])
            reply(commands[2],true)
            telemetry=boostState("rectangle",enabled=false).telemetry
            until("confirmed off") {vm.state.value.pending.isEmpty() && vm.state.value.forwardBoostEnabled==false && vm.state.value.canStart}
            vm.setForwardBoost(false);repeat(4) {pump()}
            assertEquals(3,commands.size)
        } finally {vm.disconnect();controller.pause().stop().destroy();server.shutdown()}
    }
}
