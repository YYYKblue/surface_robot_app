package cn.surface.operator

import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import cn.surface.operator.core.*
import cn.surface.operator.ui.TaskPage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1024dp-h720dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpenRouteUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun newAndLegacyScopesExplainPartialRouteAndNewPhasesAreReadable() {
        val vm=ViewModelProvider(compose.activity)[OperatorViewModel::class.java]
        var displayed by mutableStateOf(OperatorState())
        fun show(scope:String,phase:String="COMPLETE",chain:Boolean=true,fresh:Boolean=true) {
            displayed=OperatorState(connected=true,receivedAt=100,now=if(fresh) 100 else 1000,taskKind="l",
                telemetry=packet("tasks" to packet("l" to packet("fresh" to true,"completion_scope" to scope,
                    "phase" to phase,"run_id" to "mid-chain","start_edge_id" to "edge_3","current_edge_id" to "edge_4",
                    "route_chain_index" to if(chain) 0 else null,"route_chain_count" to if(chain) 2 else null,
                    "coverage_complete" to false))))
        }
        compose.runOnIdle {
            show("remaining_open_route")
            compose.activity.setContent {
                MaterialTheme { Column(Modifier.width(316.dp).verticalScroll(rememberScrollState()).padding(18.dp)) {TaskPage(displayed,vm)} }
            }
        }
        compose.onNodeWithText("开放多边清洗").assertExists()
        compose.onNodeWithText("当前墙链：1 / 2").performScrollTo().assertIsDisplayed()
        for(scope in listOf("remaining_open_route","remaining_l_route")) {
            compose.runOnIdle {show(scope,chain=scope=="remaining_open_route")}
            compose.onNodeWithText("本次仅执行剩余开放墙链，到终点停止；不返回、不补洗。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("本次剩余开放墙链执行完成\n覆盖记录仍有缺口").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("当前墙链：1 / 2").assertDoesNotExist()
        for((phase,label) in mapOf("LEFT_EXIT_WALL" to "左转换边：前行走出当前墙端",
            "LEFT_SHIFT_TO_ENTRY" to "左转换边：左移至下一墙入口","TURN_LEFT_TO_NEXT_EDGE" to "左转换边：左转对齐下一墙")) {
            compose.runOnIdle {show("remaining_open_route",phase)}
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        compose.runOnIdle {show("remaining_open_route",fresh=false)}
        compose.onNodeWithText("上次记录 · 当前墙链：1 / 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("历史结果：本次剩余开放墙链执行完成\n覆盖记录仍有缺口").performScrollTo().assertIsDisplayed()
    }
}
