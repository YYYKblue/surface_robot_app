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
class TaskRecoveryConnectionTest {
    @Test fun reconnectTargetsRunningOpenTaskAndNeverReplaysPreviousManualIntent() {
        val server=MockWebServer()
        val socket=AtomicReference<WebSocket>()
        val commands=CopyOnWriteArrayList<Pair<Int,JsonObject>>()
        val capabilities=setOf("task_selection","initial_pose","start_conditions","task_stop")
        var active=false
        fun response(connection:Int)=MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
            override fun onMessage(webSocket:WebSocket,text:String) {
                val p=wireJson.parseToJsonElement(text) as JsonObject
                commands.add(connection to p)
                if(p.str("op")=="hello") {
                    socket.set(webSocket)
                    webSocket.send(packet("type" to "hello","protocol" to "f1-operator-v1","client_id" to "me",
                        "capabilities" to JsonArray(capabilities.map(::JsonPrimitive))).toString())
                }
            }
        })
        server.enqueue(response(1));server.enqueue(response(2));server.start()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val vm=ViewModelProvider(controller.get())[OperatorViewModel::class.java]
        fun telemetry()=packet("type" to "telemetry","active_client" to "me","task_busy" to active,
            "active_tasks" to JsonArray(if(active) listOf(JsonPrimitive("l")) else emptyList()),
            "tasks" to packet(
                "rectangle" to packet("fresh" to true,"phase" to "IDLE","run_id" to "","can_start" to !active,"map_id" to "map-a"),
                "l" to packet("fresh" to true,"phase" to if(active) "FOLLOW" else "IDLE",
                    "run_id" to if(active) "running-open-task" else "","can_start" to !active,"map_id" to "map-a")),
            "localization" to packet("fresh" to true,"ready" to true,"map_id" to "map-a"),
            "state" to packet("allow_fifth_thruster" to true))
        fun pump() {
            socket.get()?.send(telemetry().toString())
            Thread.sleep(15)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        }
        fun until(description:String,condition:()->Boolean) {
            repeat(200) {pump();if(condition()) return}
            fail("Timed out: $description")
        }
        val address=server.url("/").toString().replace("http:","ws:")
        try {
            vm.connect(address)
            until("initial connection ready") {vm.state.value.fresh}
            vm.page(Page.MANUAL);vm.direction("FORWARD")
            until("first connection transmitted manual input") {
                commands.any {it.first==1 && it.second.str("op")=="manual_input" && it.second.str("direction")=="FORWARD"}
            }
            active=true
            val old=socket.get()
            vm.connect(address)
            assertEquals("rectangle",vm.state.value.taskKind)
            until("new connection focuses running open task") {
                socket.get()!==old && vm.state.value.taskKind=="l" && vm.state.value.canTaskAction("stop")
            }
            assertFalse(vm.state.value.driving)
            assertFalse(vm.state.value.manual.active)
            assertFalse(vm.state.value.canSelectTask)
            vm.task("start") // A running task cannot be restarted by this action.
            vm.task("stop")
            until("stop sent to open controller") {
                commands.any {it.first==2 && it.second.str("op")=="task" && it.second.str("action")=="stop"}
            }
            repeat(10) {pump()}
            val second=commands.filter {it.first==2}.map {it.second}
            val stop=second.single {it.str("op")=="task"}
            assertEquals("stop",stop.str("action"))
            assertEquals("l",stop.str("task_type"))
            assertTrue(stop.str("request_id").isNotEmpty())
            assertTrue(second.all {it.str("op") in setOf("hello","task")})
            assertFalse(second.any {it.str("action")=="start" || it.str("op")=="manual_input"})
        } finally {vm.disconnect();controller.pause().stop().destroy();server.shutdown()}
    }
}
