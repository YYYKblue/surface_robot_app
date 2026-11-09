package cn.surface.operator

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.surface.operator.core.*
import cn.surface.operator.ui.MapPanel
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1024dp-h720dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpectedTrackUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun legendAndMarkersFollowVisibilityFreshnessAndPreviewWithoutHardcodedValues() {
        val track=trackCases().first().obj("expected_track")
        var s by mutableStateOf(trackState(track))
        var preview by mutableStateOf(false)
        compose.runOnIdle {compose.activity.setContent {MaterialTheme {MapPanel(s,{},Modifier.fillMaxSize(),if(preview) s.map else null)}}}
        val marker="船中心停车点：4 个，距墙端 40 cm"
        compose.onNodeWithContentDescription(marker).assertExists()
        compose.onNodeWithText("船中心目标距左墙 33 cm · 开刷范围 32–36 cm · 距墙端 40 cm 停车").assertIsDisplayed()
        compose.onNodeWithText("轨迹").performClick()
        compose.onNodeWithContentDescription(marker).assertDoesNotExist()
        compose.onNodeWithText("船中心目标距左墙",substring=true).assertDoesNotExist()
        compose.onNodeWithText("轨迹").performClick()
        compose.runOnIdle {s=s.copy(now=900)}
        compose.onNodeWithContentDescription(marker).assertDoesNotExist()
        compose.onNodeWithText("船中心目标距左墙",substring=true).assertDoesNotExist()
        compose.runOnIdle {s=trackState(JsonObject(track-"basis"))}
        compose.onNodeWithText("蓝虚线：清洗目标",substring=true).assertExists()
        compose.onNodeWithContentDescription(marker).assertDoesNotExist()
        compose.runOnIdle {s=trackState(track);preview=true}
        compose.onNodeWithText("独立预览 · 不叠加当前机器人位置").assertIsDisplayed()
        compose.onNodeWithContentDescription(marker).assertDoesNotExist()
        compose.runOnIdle {preview=false;s=trackState(trackCases().first {it.str("variant")=="rotated_suffix"}.obj("expected_track"))}
        compose.onNodeWithText("距墙端 55 cm 停车",substring=true).assertIsDisplayed()
    }
}
