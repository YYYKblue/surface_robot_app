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
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1280dp-h800dp-land-xhdpi")
class TaskMapConnectionTest {
    private class Session(initial:OperatorState):AutoCloseable {
        val server=MockWebServer()
        val socket=AtomicReference<WebSocket>()
        val commands=CopyOnWriteArrayList<JsonObject>()
        var telemetry=initial.telemetry
        var catalog=initial.catalog
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val vm=ViewModelProvider(controller.get())[OperatorViewModel::class.java]
        init {
            fun response()=MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
                override fun onMessage(webSocket:WebSocket,text:String) {
                    val p=wireJson.parseToJsonElement(text).jsonObject
                    if(p.str("op")=="hello") {
                        socket.set(webSocket)
                        webSocket.send(packet("type" to "hello","protocol" to "f1-operator-v1","client_id" to "me",
                            "capabilities" to JsonArray(initial.capabilities.map(::JsonPrimitive))).toString())
                    } else {
                        commands.add(p)
                        if(p.str("op")=="mapping" && p.str("action")=="list") webSocket.send(packet("type" to "reply","op" to "mapping",
                            "request_id" to p.str("request_id"),"success" to true,"maps" to JsonArray(catalog)).toString())
                    }
                }
            })
            repeat(3) {server.enqueue(response())};server.start()
            vm.connect(server.url("/").toString().replace("http:","ws:"))
        }
        fun pump() {
            socket.get()?.send(JsonObject(telemetry+packet("type" to "telemetry")).toString())
            Thread.sleep(15);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        fun until(description:String,condition:()->Boolean) {
            repeat(150) {pump();if(condition()) return}
            fail("Timed out: $description")
        }
        fun reply(p:JsonObject,success:Boolean,pending:Boolean=false,extra:JsonObject=EMPTY) {
            socket.get().send(JsonObject(packet("type" to "reply","op" to p.str("op"),"action" to p.str("action"),
                "request_id" to p.str("request_id"),"success" to success,"pending" to pending,"message" to "test reply")+extra).toString())
        }
        fun actions(action:String)=commands.filter {it.str("action")==action}
        fun reconnect() {
            val old=socket.get();old.close(1000,"reconnect test")
            until("new hello and telemetry") {socket.get()!==old && vm.state.value.fresh}
        }
        fun capture(name:String) {
            val path=File("build/reports/contracts/$name.json");path.parentFile?.mkdirs()
            path.writeText(JsonArray(commands).toString())
        }
        override fun close() {vm.disconnect();controller.pause().stop().destroy();server.shutdown()}
    }

    @Test fun resumeUsesOriginalRunAndWaitsThroughRejectionTimeoutReconnectAndAcquiring() {
        Session(resumeState()).use {s ->
            val vm=s.vm
            s.until("paused open task selected") {vm.state.value.taskKind=="l" && vm.state.value.canResumeTask}
            vm.task("resume");vm.task("resume")
            s.until("one resume") {s.actions("resume").size==1}
            val first=s.actions("resume").single()
            assertEquals("l",first.str("task_type"));assertEquals("original-run",first.str("run_id"))
            assertTrue(first.str("request_id").isNotBlank());assertEquals("PAUSED",vm.state.value.task.str("phase"))
            s.reply(first,false)
            s.until("rejection unlocks explicit retry") {vm.state.value.canResumeTask}
            vm.task("resume")
            s.until("second explicit resume") {s.actions("resume").size==2}
            val second=s.actions("resume").last()
            assertNotEquals(first.str("request_id"),second.str("request_id"))
            s.reply(second,false,pending=true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            s.reconnect()
            assertEquals(2,s.actions("resume").size);assertEquals(1,vm.state.value.pending.size)
            s.telemetry=resumeState(fields=packet("phase" to "ACQUIRING")).telemetry
            s.until("acquiring is not confirmation") {vm.state.value.task.str("phase")=="ACQUIRING"}
            assertEquals(1,vm.state.value.pending.size);assertTrue(vm.state.value.canTaskAction("cancel"))
            s.telemetry=resumeState(fields=packet("phase" to "FOLLOW","resume_sequence" to 3)).telemetry
            s.until("same run progressed without replay") {vm.state.value.pending.isEmpty()}
            assertEquals(2,s.actions("resume").size);assertEquals("original-run",vm.state.value.task.str("run_id"))
            vm.task("cancel")
            s.until("cancel") {s.actions("cancel").size==1}
            assertEquals("l",s.actions("cancel").single().str("task_type"))
            s.capture("resume-wire")
        }
    }

    @Test fun snapshotDeletionRequiresConfirmationAndNeverDeletesSameNamePublishedMapOrReplays() {
        val id="0123456789abcdef0123456789abcdef"
        val published=packet("name" to "saved","valid" to true)
        val snapshot=packet("name" to "saved","recoverable" to true,"snapshot_id" to id)
        Session(mapManagementState().copy(catalog=listOf(published,snapshot))).use {s ->
            val vm=s.vm
            s.until("connected") {vm.state.value.fresh}
            vm.page(Page.MAPS)
            s.until("same-name entries") {vm.state.value.catalog.size==2 && vm.state.value.pending.isEmpty()}
            vm.selectMap("saved",id);vm.mapping("delete",confirmed=true)
            repeat(3) {s.pump()};assertTrue(s.actions("delete").isEmpty())
            vm.requestMapDeletion();assertEquals(MapSelection("saved",id),vm.state.value.deleteTarget)
            vm.dismissMapDeletion();vm.confirmMapDeletion()
            repeat(3) {s.pump()};assertTrue(s.actions("delete").isEmpty())
            vm.requestMapDeletion();vm.selectMap("saved");vm.confirmMapDeletion()
            repeat(3) {s.pump()};assertTrue(s.actions("delete").isEmpty())
            vm.selectMap("saved",id);vm.requestMapDeletion();vm.confirmMapDeletion();vm.confirmMapDeletion()
            s.until("one exact snapshot delete") {s.actions("delete").size==1}
            val first=s.actions("delete").single()
            assertEquals(id,first.str("snapshot_id"));assertEquals(JsonPrimitive(true),first["confirm"])
            assertEquals(2,vm.state.value.catalog.size) // No optimistic disappearance.
            s.reply(first,false)
            s.until("rejected deletion refreshed") {vm.state.value.pending.isEmpty() && vm.state.value.canMapping("delete")}
            assertEquals(2,vm.state.value.catalog.size)
            vm.requestMapDeletion();vm.confirmMapDeletion()
            s.until("second explicit delete") {s.actions("delete").size==2}
            val second=s.actions("delete").last()
            s.reply(second,false,pending=true)
            s.reconnect()
            assertEquals(2,s.actions("delete").size);assertEquals(1,vm.state.value.pending.size)
            s.catalog=listOf(published)
            s.telemetry=JsonObject(s.telemetry+packet("runtime" to JsonObject(s.telemetry.obj("runtime")+packet("last_operation" to
                packet("request_id" to second.str("request_id"),"success" to true)))))
            s.until("last operation resolves deletion and refreshes") {vm.state.value.pending.isEmpty() && vm.state.value.catalog.size==1}
            assertTrue(vm.state.value.catalog.single().flag("valid"));assertEquals("",vm.state.value.selectedSnapshotId)
            assertNull(vm.state.value.preview);assertEquals(2,s.actions("delete").size)
            vm.mapping("unload")
            s.until("unload") {s.actions("unload").size==1}
            val unload=s.actions("unload").single()
            assertFalse("name" in unload)
            s.reply(unload,true)
            s.telemetry=packet("runtime" to packet("mode" to "idle","session_id" to "unloaded","map_available" to false))
            s.until("idle") {vm.state.value.fresh && vm.state.value.runtime.str("mode")=="idle" && vm.state.value.pending.isEmpty()}
            assertTrue(vm.state.value.catalog.single().flag("valid")) // Unload preserves files.
            s.capture("map-management-wire")
        }
    }
}
