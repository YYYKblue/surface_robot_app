package cn.surface.operator

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import cn.surface.operator.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Exercise the actual two-controller wiring, including lifecycle and task isolation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DualControlViewModelTest {
    private class Fixture:AutoCloseable {
        val vm=OperatorViewModel(ApplicationProvider.getApplicationContext<Application>())
        private val store=ViewModelStore().apply {put("operator",vm)}
        fun until(description:String,predicate:()->Boolean) {
            repeat(120) {
                Thread.sleep(5)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
                if(predicate()) return
            }
            fail(description)
        }
        fun underReady()=until("underwater ready") {vm.underwater.value.canDrive}
        fun both() {vm.connect("",true);underReady();vm.page(Page.MANUAL);until("surface ready") {vm.state.value.manualAllowed}}
        override fun close() {store.clear()}
    }
    @Test fun underwaterWorksWithSurfaceOfflineAndEachDisconnectIsIndependent() {
        Fixture().use {f ->
            f.vm.connectUnderwater("",true);f.underReady()
            assertFalse(f.vm.state.value.connected)
            f.vm.underDirection("FORWARD")
            assertEquals("FORWARD",f.vm.underwater.value.direction)
            f.vm.disconnect()
            assertEquals("FORWARD",f.vm.underwater.value.direction)
            f.vm.stopUnderwater()
            f.both()
            f.vm.direction("FORWARD")
            assertTrue(f.vm.state.value.driving)
            f.vm.disconnectUnderwater()
            assertTrue(f.vm.state.value.driving)
            assertEquals("FORWARD",f.vm.state.value.manual.direction)
        }
    }
    @Test fun localStopsAreIndependentAndGlobalStopClearsBoth() {
        Fixture().use {f ->
            f.both()
            f.vm.direction("FORWARD");f.vm.underDirection("LEFT")
            f.vm.stopSurfaceExplicit()
            assertFalse(f.vm.state.value.driving)
            assertEquals("LEFT",f.vm.underwater.value.direction)
            f.vm.direction("RIGHT");f.vm.stopUnderwater()
            assertEquals("RIGHT",f.vm.state.value.manual.direction)
            assertEquals("STOP",f.vm.underwater.value.direction)
            f.underReady();f.vm.underDirection("REVERSE")
            f.vm.explicitStop()
            assertFalse(f.vm.state.value.driving)
            assertEquals("STOP",f.vm.underwater.value.direction)
        }
    }
    @Test fun surfaceTaskAndPauseDoNotLockUnderwaterAndGlobalStopDoesNotCancelTask() {
        Fixture().use {f ->
            f.vm.connect("",true)
            f.until("pose ready") {f.vm.state.value.canPose}
            f.vm.pick(true)
            f.vm.candidate(Pose(1.0,1.0,0.0,f.vm.state.value.map!!.revision))
            f.vm.publishPose()
            f.until("task start available") {f.vm.state.value.canStart}
            f.vm.task("start")
            f.until("task running") {f.vm.state.value.canTaskAction("pause")}
            f.underReady();f.vm.underDirection("FORWARD")
            assertEquals("FORWARD",f.vm.underwater.value.direction)
            f.vm.task("pause")
            assertEquals("FORWARD",f.vm.underwater.value.direction)
            f.until("surface paused") {f.vm.state.value.task.str("phase")=="PAUSED"}
            assertTrue(f.vm.underwater.value.canDrive)
            f.vm.explicitStop()
            assertEquals("STOP",f.vm.underwater.value.direction)
            assertEquals("PAUSED",f.vm.state.value.task.str("phase"))
            assertTrue(f.vm.state.value.busy)
        }
    }
    @Test fun lifecycleNavigationAndExportRequireNewInput() {
        Fixture().use {f ->
            f.both()
            val interruptions=listOf<()->Unit>(
                {f.vm.focusLost()},
                {f.vm.foreground(false);f.vm.foreground(true)},
                {f.vm.page(Page.SETTINGS);f.vm.page(Page.MANUAL)},
                {assertTrue(f.vm.beginLogExport());assertFalse(f.vm.underwater.value.canDrive);f.vm.finishLogExport(null)}
            )
            for(interrupt in interruptions) {
                f.underReady();f.vm.underDirection("RIGHT");f.vm.direction("FORWARD")
                assertEquals("RIGHT",f.vm.underwater.value.direction)
                interrupt()
                assertEquals("STOP",f.vm.underwater.value.direction)
                assertFalse(f.vm.state.value.driving)
                f.underReady()
                assertEquals("STOP",f.vm.underwater.value.direction)
                assertEquals("STOP",f.vm.state.value.manual.direction)
            }
        }
    }
}
