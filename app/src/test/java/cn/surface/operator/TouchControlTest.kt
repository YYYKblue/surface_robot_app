package cn.surface.operator

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import cn.surface.operator.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1024dp-h720dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TouchControlTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun holdReleaseAndCancelHaveDifferentAuxiliarySemantics() {
        val vm=ViewModelProvider(compose.activity)[OperatorViewModel::class.java]
        try {
            compose.runOnIdle {vm.connect("",true)}
            // Compose's frame clock alone does not advance Handler-based gateway ticks.
            repeat(20) {Thread.sleep(30);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))}
            compose.runOnIdle {assertTrue(vm.state.value.fresh);assertNotNull(vm.state.value.map)}
            compose.onNodeWithText("手动驾驶").performClick()
            compose.onNodeWithText("启用手动驾驶").performClick()
            compose.runOnIdle {vm.manual(vm.state.value.manual.copy(brush=true))}
            val forward=compose.onNodeWithContentDescription("按住↑，松开停止主驱动")
            forward.performTouchInput {down(center)}
            compose.runOnIdle {assertEquals("FORWARD",vm.state.value.manual.direction);assertTrue(vm.state.value.manual.brush)}
            forward.performTouchInput {up()}
            compose.runOnIdle {assertEquals("STOP",vm.state.value.manual.direction);assertTrue(vm.state.value.manual.brush);assertTrue(vm.state.value.driving)}
            forward.performTouchInput {down(center)}
            forward.performTouchInput {cancel()}
            compose.runOnIdle {assertFalse(vm.state.value.manual.active);assertFalse(vm.state.value.driving)}
        } finally {compose.runOnIdle {vm.disconnect()}}
    }
}
