package cn.surface.operator

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.surface.operator.core.*
import cn.surface.operator.ui.ForwardBoostPanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1024dp-h720dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForwardBoostUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun switchUsesRobotStateAndLocksUntilConfirmationOrOnOldGateway() {
        var s by mutableStateOf(boostState())
        var requested:Boolean?=null
        compose.runOnIdle {compose.activity.setContent {MaterialTheme {Column {ForwardBoostPanel(s) {requested=it}}}}}
        val toggle=compose.onNodeWithContentDescription("主电机提速开关")
        toggle.assertIsEnabled().assertIsOff().performClick()
        compose.runOnIdle {assertEquals(true,requested)}
        toggle.assertIsOff() // Clicking cannot optimistically turn the switch on.
        compose.runOnIdle {s=s.copy(pending=mapOf("b" to Pending("b",packet("op" to "task","action" to "set_forward_boost","task_type" to "l"),100)))}
        toggle.assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("提速设置尚未确认，等待机器人回复。").assertIsDisplayed()
        compose.runOnIdle {s=boostState(enabled=true)}
        toggle.assertIsEnabled().assertIsOn()
        compose.runOnIdle {s=boostState(enabled=true,phase="PAUSED")}
        toggle.assertIsNotEnabled().assertIsOn()
        compose.runOnIdle {s=s.copy(now=900)}
        compose.onNodeWithText("主电机提速：状态未知").assertIsDisplayed()
        toggle.assertIsNotEnabled()
        compose.runOnIdle {s=boostState().copy(capabilities=emptySet())}
        compose.onNodeWithText("提速功能需要新版 Nano 网关和清洗控制器。").assertIsDisplayed()
        toggle.assertIsNotEnabled()
    }
}
