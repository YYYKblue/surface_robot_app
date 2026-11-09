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
class DiagnosticsUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun pump() {repeat(20) {Thread.sleep(25);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))}}
    @Test fun gatewayEventsCanBeFilteredExpandedAndClearedWithoutReplay() {
        val vm=ViewModelProvider(compose.activity)[OperatorViewModel::class.java]
        try {
            compose.runOnIdle {vm.connect("",true)};pump()
            compose.onNodeWithText("诊断",useUnmergedTree=true).performClick()
            compose.onNodeWithText("串口桥").performClick()
            compose.onNodeWithText("搜索原因、参数或事件编号").performTextInput("ACK progress")
            compose.onNodeWithText("下位机 ACK 确认超时 (ACK progress timeout)").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("原始详情：",substring=true).assertExists()
            compose.onNodeWithText("展开日志").performClick()
            compose.onNodeWithText("关闭展开").assertIsDisplayed().performClick()
            compose.onNodeWithText("清空本地日志").performClick()
            compose.onNodeWithText("清空",useUnmergedTree=true).performClick()
            pump()
            compose.runOnIdle {
                assertTrue(vm.state.value.logs.none {it.eventId!=null})
                assertTrue(vm.state.value.connected)
                assertFalse(vm.state.value.driving)
            }
            compose.onNodeWithText("暂无符合条件的日志").assertIsDisplayed()
        } finally {compose.runOnIdle {vm.disconnect()}}
    }
}
