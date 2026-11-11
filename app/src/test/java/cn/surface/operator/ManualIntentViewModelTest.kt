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
        fun modernFresh() {
            fresh()
            mutable.value=mutable.value.copy(capabilities=setOf(CONTINUOUS_DRIVE_CAPABILITY),
                telemetry=packet("active_client" to "me","control_fresh" to true,
                    "drive_output" to packet("min" to 0,"max" to 100,"step" to 1,
                        "firmware_protocol_version" to 3,"available" to true),
                    "state" to packet("allow_fifth_thruster" to true,"connected" to true,
                        "connection_id" to "serial-1","source" to "NANO","epoch" to 1,
                        "state" to "READY","armed" to true,"result" to "OK",
                        "ctrl_age_sec" to 0.0,"ack_age_sec" to 0.0)))
        }
        fun drive() {
            vm.direction("FORWARD");vm.brush(true);vm.thrust(true)
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
            f.vm.speed(50)
            f.sender.pump()
            assertFalse(f.vm.state.value.driving)
            assertFalse(f.vm.state.value.manual.active)
            assertTrue(f.sender.expired)
            assertTrue(f.sent.any {it.str("reason")=="gui_input_expired"})
            val count=f.sent.count {it.str("op")=="manual_input"}
            f.vm.direction("FORWARD");f.sender.pump()
            assertTrue(f.vm.state.value.driving)
            assertFalse(f.vm.state.value.manual.brush)
            assertFalse(f.vm.state.value.manual.thrust)
            assertTrue(f.sent.count {it.str("op")=="manual_input"}>count)
        }
    }
    @Test fun directPressAndAuxiliarySwitchStartWithoutAnEnableStep() {
        Fixture().use {f ->
            assertFalse(f.vm.state.value.driving)
            assertFalse(f.sender.armed)
            f.vm.speed(50);f.vm.driveMode("FRONT");f.sender.pump()
            assertTrue(f.sent.isEmpty()) // Configuration/observation does not claim the gateway.
            f.vm.direction("LEFT");f.sender.pump()
            assertEquals("LEFT",f.sent.last().str("direction"))
            assertEquals(50,f.sent.last().long("speed_percent").toInt())
            f.vm.explicitStop();f.sender.pump()
            assertFalse(f.sender.armed)
            f.vm.brush(true);f.sender.pump()
            assertTrue(f.sent.last().flag("brush_enabled"))
            assertEquals("STOP",f.sent.last().str("direction"))
            f.vm.thrust(true);f.sender.pump()
            assertTrue(f.sent.last().flag("thrust_enabled"))
            f.vm.direction("FORWARD");f.sender.pump()
            f.vm.direction("STOP");f.sender.pump()
            assertTrue(f.vm.state.value.manual.brush)
            assertTrue(f.vm.state.value.manual.thrust)
            assertEquals("STOP",f.vm.state.value.manual.direction)
        }
    }
    @Test fun expiryAndFocusRecoveryOnlyAcceptNewActionsAndDoNotRestoreAuxiliaries() {
        Fixture().use {f ->
            f.drive();f.vm.focusLost()
            f.fresh();f.sender.pump()
            assertFalse(f.vm.state.value.driving)
            assertFalse(f.vm.state.value.manual.active)
            f.vm.direction("REVERSE");f.sender.pump()
            assertEquals("REVERSE",f.vm.state.value.manual.direction)
            assertFalse(f.vm.state.value.manual.brush)
            assertFalse(f.vm.state.value.manual.thrust)
            f.vm.foreground(false)
            f.vm.direction("FORWARD");f.sender.pump()
            assertFalse(f.vm.state.value.manual.active)
            f.vm.foreground(true)
            f.sender.pump()
            assertFalse(f.vm.state.value.manual.active)
            f.vm.brush(true);f.sender.pump()
            assertTrue(f.vm.state.value.manual.brush)
            assertEquals("STOP",f.vm.state.value.manual.direction)
        }
    }
    @Test fun staleTelemetryCannotBeBypassedByANewPressAndRecoveryIsIdle() {
        Fixture().use {f ->
            f.drive()
            ShadowSystemClock.advanceBy(Duration.ofMillis(801))
            f.vm.direction("LEFT");f.sender.pump()
            assertFalse(f.vm.state.value.manual.active)
            assertFalse(f.vm.state.value.driving)
            f.fresh();f.sender.pump()
            assertFalse(f.vm.state.value.manual.active)
            val count=f.sent.count {it.str("op")=="manual_input"}
            f.vm.direction("LEFT");f.sender.pump()
            assertTrue(f.sent.count {it.str("op")=="manual_input"}>count)
            assertEquals("LEFT",f.vm.state.value.manual.direction)
        }
    }
    @Test fun v3PressAutomaticallySendsNeutralAndZeroSpeedCannotReviveHeldMotion() {
        Fixture().use {f ->
            f.modernFresh()
            f.vm.direction("FORWARD");f.sender.pump()
            assertEquals("STOP",f.sent.last().str("direction"))
            ShadowSystemClock.advanceBy(Duration.ofMillis(50));f.sender.pump()
            assertEquals("FORWARD",f.sent.last().str("direction"))
            f.vm.brush(true);f.sender.pump()
            f.vm.speed(0);f.sender.pump()
            assertEquals("STOP",f.vm.state.value.manual.direction)
            assertTrue(f.vm.state.value.manual.brush)
            f.vm.speed(37);f.sender.pump()
            assertEquals("STOP",f.vm.state.value.manual.direction)
            assertTrue(f.vm.state.value.manual.brush)
            assertEquals(37,f.vm.state.value.manual.speed)
        }
    }
    @Test fun idleResumeWithSameRobotIdentityStillSendsANewNeutralFrame() {
        Fixture().use {f ->
            f.modernFresh();f.vm.direction("FORWARD");f.sender.pump()
            ShadowSystemClock.advanceBy(Duration.ofMillis(50));f.sender.pump()
            f.vm.direction("STOP");f.sender.pump()
            // Refresh the GUI while remaining idle longer than the gateway watchdog.
            repeat(3) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(100))
                f.modernFresh()
                val refresh=OperatorViewModel::class.java.getDeclaredMethod("refreshManualIntent").apply {isAccessible=true}
                refresh.invoke(f.vm);f.sender.pump()
            }
            assertTrue(f.vm.state.value.driving)
            val count=f.sent.size
            f.vm.direction("REVERSE");f.sender.pump()
            assertEquals(count+1,f.sent.size)
            assertEquals("STOP",f.sent.last().str("direction"))
            ShadowSystemClock.advanceBy(Duration.ofMillis(50));f.sender.pump()
            assertEquals("REVERSE",f.sent.last().str("direction"))
        }
    }
    @Test fun auxiliaryAtZeroRemainsSelectedButDoesNotRestoreExpiredOutput() {
        Fixture().use {f ->
            f.vm.brushPercent(0);f.vm.brush(true);f.sender.pump()
            assertTrue(f.vm.state.value.driving)
            f.mutable.value=StateReducer.tick(f.vm.state.value,SystemClock.elapsedRealtime())
            assertTrue(f.vm.state.value.manual.brush)
            assertTrue(f.sent.isEmpty())
            f.vm.brushPercent(25);f.sender.pump()
            assertTrue(f.sent.last().flag("brush_enabled"))
            ShadowSystemClock.advanceBy(Duration.ofMillis(251))
            f.fresh();f.vm.brushPercent(50);f.sender.pump()
            assertFalse(f.vm.state.value.manual.brush)
            assertFalse(f.vm.state.value.driving)
            assertEquals(50,f.vm.state.value.manual.brushPercent)
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
