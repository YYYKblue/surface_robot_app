package cn.surface.operator

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import cn.surface.operator.connection.GatewayConnection
import cn.surface.operator.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ManualStopConnectionTest {
    @Test fun cancelledManualStopCannotRestoreStateFromBeforeSynchronousDisconnect() {
        checkStop {it.stopManual("test_interruption")}
    }

    @Test fun explicitManualStopCannotRestoreStateFromBeforeSynchronousDisconnect() {
        checkStop {it.explicitStop()}
    }

    private fun checkStop(stop:(OperatorViewModel)->Unit) {
        val vm=OperatorViewModel(ApplicationProvider.getApplicationContext<Application>())
        val store=ViewModelStore().apply {put("operator",vm)}
        // Exercise the real disconnect reducer without adding production test hooks.
        val field=OperatorViewModel::class.java.getDeclaredField("mutable").apply {isAccessible=true}
        @Suppress("UNCHECKED_CAST")
        val mutable=field.get(vm) as MutableStateFlow<OperatorState>
        val lost=OperatorViewModel::class.java.getDeclaredMethod("lost",String::class.java).apply {isAccessible=true}
        val read=Pending("preview",packet("op" to "mapping","action" to "preview"),100)
        val task=Pending("task",packet("op" to "task","action" to "start","task_type" to "l"),100)
        mutable.value=OperatorState(connected=true,clientId="me",receivedAt=100,now=100,
            capabilities=setOf("mapping","task_stop"),page=Page.MANUAL,driving=true,
            manual=ManualIntent("FORWARD",brush=true,thrust=true),
            pending=mapOf(read.id to read,task.id to task),
            telemetry=packet("active_client" to "me"))
        var sends=0
        val socket=object:GatewayConnection {
            override fun connect() {}
            override fun close() {}
            override fun send(value:JsonObject):Boolean {
                sends++
                assertEquals("manual_stop",value.str("op"))
                assertFalse(vm.state.value.driving)
                assertFalse(vm.state.value.manual.active)
                lost.invoke(vm,"test backpressure")
                return false
            }
        }
        OperatorViewModel::class.java.getDeclaredField("connection").apply {isAccessible=true}.set(vm,socket)
        try {
            stop(vm)
            val after=vm.state.value
            assertEquals(1,sends)
            assertFalse(after.connected)
            assertEquals("",after.clientId)
            assertTrue(after.capabilities.isEmpty())
            assertEquals(-1L,after.receivedAt)
            assertFalse(after.driving)
            assertFalse(after.manual.active)
            assertTrue(after.message.contains("停止请求未发送"))
            assertEquals(setOf("task"),after.pending.keys)
        } finally {store.clear()}
    }
}
