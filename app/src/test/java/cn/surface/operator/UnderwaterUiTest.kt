package cn.surface.operator

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import cn.surface.operator.core.*
import cn.surface.operator.ui.UnderwaterPanel
import org.junit.Assert.*
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
class UnderwaterUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun ready()=UnderwaterState(connection="水下已连接",connected=true,clientId="under-ui",sessionId="test",visible=true,
        receivedAt=1000,now=1000,report=packet("connected" to true,"control_fresh" to true,
            "report_age_sec" to 0.0,"connection_id" to "serial-1","control_generation" to 1,
            "epoch" to 1,"source" to "NANO","firmware_protocol_version" to 6,"available" to true,
            "motion_enabled" to true,"armed" to true,"result" to "OK","state" to "READY","owner" to null,
            "direction" to "STOP","speed_percent" to 0))

    @Test fun releaseStopsAndConflictingDirectionsStayStoppedUntilEveryFingerIsLifted() {
        var s by mutableStateOf(ready())
        val stops=mutableListOf<String>()
        compose.runOnIdle {compose.activity.setContent {MaterialTheme {
            Column(Modifier.width(264.dp).verticalScroll(rememberScrollState()).padding(18.dp)) {
                UnderwaterPanel(s,{s=s.copy(direction=it)},{s=s.copy(speed=it)},
                    {stops.add(it);s=s.copy(direction="STOP")},{})
            }
        }}}
        val pad=compose.onNodeWithTag("underwater-direction-pad")
        pad.performTouchInput {down(0,Offset(centerX,height/6f))}
        compose.runOnIdle {assertEquals("FORWARD",s.direction)}
        pad.performTouchInput {up(0)}
        compose.runOnIdle {assertEquals("STOP",s.direction);assertTrue(stops.isEmpty())}
        pad.performTouchInput {down(0,Offset(centerX,height/6f))}
        pad.performTouchInput {down(1,Offset(width/6f,centerY))}
        compose.runOnIdle {assertEquals("STOP",s.direction);assertTrue(stops.contains("under_touch_cancelled"))}
        pad.performTouchInput {up(1)}
        compose.runOnIdle {assertEquals("STOP",s.direction)}
        pad.performTouchInput {down(2,Offset(width*5f/6f,centerY));up(2);up(0)}
        compose.runOnIdle {assertEquals("STOP",s.direction)}
        pad.performTouchInput {down(0,Offset(width/6f,centerY))}
        compose.runOnIdle {assertEquals("LEFT",s.direction)}
        pad.performTouchInput {cancel()}
        compose.runOnIdle {assertEquals("STOP",s.direction)}
        pad.performTouchInput {down(0,Offset(width*5f/6f,centerY))}
        compose.runOnIdle {assertEquals("RIGHT",s.direction)}
        pad.performTouchInput {moveTo(0,Offset(width+20f,centerY))}
        compose.runOnIdle {assertEquals("STOP",s.direction)}
        pad.performTouchInput {moveTo(0,Offset(width*5f/6f,centerY));up(0)}
        compose.runOnIdle {assertEquals("STOP",s.direction)}
    }

    @Test fun zeroAndIdentityChangesCancelHeldGestureAndNarrowPanelRemainsScrollable() {
        var s by mutableStateOf(ready())
        val stops=mutableListOf<String>()
        compose.runOnIdle {compose.activity.setContent {MaterialTheme {
            Column(Modifier.width(264.dp).verticalScroll(rememberScrollState()).padding(18.dp)) {
                UnderwaterPanel(s,{s=s.copy(direction=it)},
                    {s=s.copy(speed=it,direction=if(it==0) "STOP" else s.direction)},
                    {stops.add(it);s=s.copy(direction="STOP")},{})
            }
        }}}
        val pad=compose.onNodeWithTag("underwater-direction-pad")
        pad.performTouchInput {down(0,Offset(centerX,height/6f))}
        compose.runOnIdle {s=s.copy(speed=0,direction="STOP")}
        compose.runOnIdle {assertTrue(stops.contains("under_control_changed"));s=s.copy(speed=37)}
        pad.performTouchInput {moveBy(0,Offset(1f,0f));up(0)}
        compose.runOnIdle {assertEquals("STOP",s.direction)}
        pad.performTouchInput {down(0,Offset(centerX,height/6f))}
        compose.runOnIdle {
            assertEquals("FORWARD",s.direction)
            s=s.copy(report=packet(*s.report.map {(k,v)->k to v}.toTypedArray(),"control_generation" to 2),direction="STOP")
        }
        pad.performTouchInput {down(1,Offset(width/6f,centerY));up(1);up(0)}
        compose.runOnIdle {assertEquals("STOP",s.direction)}
        compose.onNodeWithContentDescription("水下行走输出百分比").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) {it(100f)}
        compose.runOnIdle {assertEquals(100,s.speed)}
        compose.onNodeWithText("H7 回报：",substring=true).performScrollTo().assertIsDisplayed()
        capture("tablet-underwater-narrow")
        compose.runOnIdle {s=s.copy(now=1250)}
        compose.onNodeWithText("H7 回报未收到或已过期（250 ms）").performScrollTo().assertIsDisplayed()
        capture("tablet-underwater-expired")
    }

    private fun capture(name:String) {
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output=File("build/reports/screenshots/$name.png");output.parentFile?.mkdirs()
            output.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
}
