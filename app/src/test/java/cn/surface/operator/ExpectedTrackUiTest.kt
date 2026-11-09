package cn.surface.operator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.surface.operator.core.*
import cn.surface.operator.ui.MapPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1024dp-h720dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpectedTrackUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun preparedMapImageKeepsPaletteOrientationAndStableCache() {
        val grid=GridMap(2,2,.05,Point(0.0,0.0),0.0,1,"map",byteArrayOf(-1,0,51,100))
        val image=grid.prepareImage()
        assertSame(image,grid.prepareImage())
        assertEquals(0xFF536875.toInt(),image.getPixel(0,0))
        assertEquals(0xFF536875.toInt(),image.getPixel(1,0))
        assertEquals(0xFFEEF2F3.toInt(),image.getPixel(0,1))
        assertEquals(0xFFFFFFFF.toInt(),image.getPixel(1,1))
    }
    private fun capture(name:String,width:Int,height:Int) {
        repeat(3) {
            compose.runOnIdle {
                val view=compose.activity.window.decorView
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                view.layout(0,0,width,height)
            }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            compose.activity.window.decorView.draw(Canvas(bitmap))
            val output=File("build/reports/screenshots/$name.png");output.parentFile?.mkdirs()
            output.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun onlyHistoryIsShownEvenWhenOldGatewaySendsExpectedTrackAndScan() {
        var s by mutableStateOf(historyState())
        var preview by mutableStateOf(false)
        compose.runOnIdle {compose.activity.setContent {MaterialTheme {MapPanel(s,{},Modifier.fillMaxSize(),if(preview) s.map else null)}}}
        compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("现场地图：实际轨迹 3 段").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("绿色箭头：机器人位置 · 绿线：实际轨迹").assertIsDisplayed()
        compose.onNodeWithText("雷达").assertDoesNotExist()
        compose.onNodeWithText("船中心目标距左墙",substring=true).assertDoesNotExist()
        compose.onNodeWithContentDescription("船中心停车点",substring=true).assertDoesNotExist()
        compose.onNodeWithText("历史轨迹").performClick()
        compose.onNodeWithContentDescription("现场地图：实际轨迹 0 段").assertExists()
        compose.onNodeWithText("历史轨迹").performClick()
        compose.runOnIdle {s=s.copy(now=900)}
        compose.onNodeWithContentDescription("现场地图：实际轨迹 3 段").assertExists()
        compose.onNodeWithText("遥测已过期 · 仅显示地图与历史轨迹").assertIsDisplayed()
        compose.runOnIdle {preview=true}
        compose.onNodeWithText("独立预览 · 不叠加当前机器人位置").assertIsDisplayed()
        compose.onNodeWithContentDescription("现场地图：实际轨迹 3 段").assertDoesNotExist()
        // Finish visibility assertions before overriding decor dimensions for export:
        // Robolectric's window-visible frame still uses the configured device size.
        compose.runOnIdle {preview=false;s=s.copy(now=100)}
        compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("现场地图：实际轨迹 3 段").fetchSemanticsNodes().isNotEmpty()}
        capture("tablet-stream-history",2560,1600)
        capture("tablet-stream-history-compact",1600,2000)
    }
    @Test fun mappingRevisionKeepsZoomAndNewSessionResetsView() {
        var s by mutableStateOf(historyState().copy(mapSession="mapping-1"))
        compose.runOnIdle {compose.activity.setContent {MaterialTheme {MapPanel(s,{},Modifier.fillMaxSize())}}}
        compose.onNodeWithText("＋").performClick()
        compose.onNodeWithText("1.4×").assertIsDisplayed()
        compose.runOnIdle {s=s.copy(map=GridMap(170,110,.05,Point(-.2,-.1),0.0,2,"map",ByteArray(18700)))}
        compose.onNodeWithText("1.4×").assertIsDisplayed()
        compose.runOnIdle {s=s.copy(mapSession="mapping-2")}
        compose.onNodeWithText("1.0×").assertIsDisplayed()
    }
}
