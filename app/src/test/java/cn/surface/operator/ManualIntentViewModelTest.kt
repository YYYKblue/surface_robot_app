package cn.surface.operator

import android.app.Application
import android.os.SystemClock
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import cn.surface.operator.connection.*
import cn.surface.operator.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ManualIntentViewModelTest {
    private class Fixture:AutoCloseable {
        val vm=OperatorViewModel(ApplicationProvider.getApplicationContext<Application>())
        val store=ViewModelStore().apply {put("operator",vm)}
        val sent=CopyOnWriteArrayList<JsonObject>()
        @Suppress("UNCHECKED_CAST")
        val mutable=OperatorViewModel::class.java.getDeclaredField("mutable").apply {isAccessible=true}
            .get(vm) as MutableStateFlow<OperatorState>
        val sender=OperatorViewModel::class.java.getDeclaredField("manualSender").apply {isAccessible=true}
            .get(vm) as ManualSender
        init {
            val socket=object:GatewayConnection {
                override fun connect() {}
                override fun close() {}
                override fun send(value:JsonObject):Boolean {sent.add(value);return true}
            }
            OperatorViewModel::class.java.getDeclaredField("connection").apply {isAccessible=true}.set(vm,socket)
            sender.bind(0,socket);sender.activate(0)
            fresh()
        }
        fun fresh() {
            val now=SystemClock.elapsedRealtime()
            mutable.value=mutable.value.copy(connected=true,clientId="me",page=Page.MANUAL,
                now=now,receivedAt=now,telemetry=packet("active_client" to "me",
                    "state" to packet("allow_fifth_thruster" to true)))
        }
        fun drive() {
            vm.armManual();vm.manual(ManualIntent("FORWARD",brush=true,thrust=true))
            sender.pump()
            assertTrue(sent.any {it.str("direction")=="FORWARD"})
        }
        override fun close() {store.clear()}
    }
    @Test fun resumedGuiCannotRefreshExpiredHoldBeforeHandlingQueuedExpiry() {
        Fixture().use {f ->
            f.drive()
            // Advance monotonic time without executing Main callbacks or its GUI heartbeat.
            ShadowSystemClock.advanceBy(Duration.ofMillis(251))
            f.fresh() // Even a newly received robot state cannot revive an old GUI hold.
            f.vm.manual(f.vm.state.value.manual.copy(speed=50))
            f.sender.pump()
            assertFalse(f.vm.state.value.driving)
            assertFalse(f.vm.state.value.manual.active)
            assertTrue(f.sender.expired)
            assertTrue(f.sent.any {it.str("reason")=="gui_input_expired"})
            val count=f.sent.count {it.str("op")=="manual_input"}
            f.vm.direction("FORWARD");f.sender.pump()
            assertEquals(count,f.sent.count {it.str("op")=="manual_input"})
            f.vm.armManual();f.vm.direction("FORWARD");f.sender.pump()
            assertTrue(f.vm.state.value.driving)
            assertFalse(f.vm.state.value.manual.brush)
            assertFalse(f.vm.state.value.manual.thrust)
            assertTrue(f.sent.count {it.str("op")=="manual_input"}>count)
        }
    }
    @Test fun focusAndLogExportRevokeBackgroundIntentBeforeUiContinues() {
        Fixture().use {f ->
            f.drive();f.vm.focusLost();f.sender.pump()
            assertFalse(f.sender.armed);assertFalse(f.vm.state.value.manual.active)
            f.drive()
            assertTrue(f.vm.beginLogExport())
            assertFalse(f.sender.armed);assertFalse(f.vm.state.value.driving)
            assertEquals("diagnostics_export",f.sent.last().str("reason"))
            val count=f.sent.size
            ShadowSystemClock.advanceBy(Duration.ofMillis(400));f.sender.pump()
            assertEquals(count,f.sent.size)
            f.vm.finishLogExport(null)
            assertFalse(f.vm.state.value.driving)
        }
    }
    @Test fun guiTickAfterLongStallDoesNotDuplicateTheBackgroundStop() {
        Fixture().use {f ->
            f.drive()
            ShadowSystemClock.advanceBy(Duration.ofMillis(850));f.sender.pump()
            assertEquals(1,f.sent.count {it.str("op")=="manual_stop"})
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(f.vm.state.value.driving)
            assertEquals(1,f.sent.count {it.str("op")=="manual_stop"})
            assertTrue(f.vm.state.value.logs.any {it.reason=="gui_input_expired"})
        }
    }
}
