package cn.surface.operator

import android.graphics.Bitmap
import android.graphics.Canvas
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
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1024dp-h720dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DualRobotUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun simultaneousFingersControlBothRobotsAndReleaseIndependently() {
        val vm=ViewModelProvider(compose.activity)[OperatorViewModel::class.java]
        try {
            compose.runOnIdle {vm.connect("",true)}
            repeat(20) {Thread.sleep(30);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))}
            compose.runOnIdle {vm.page(Page.MANUAL)}
            compose.runOnIdle {assertTrue(vm.state.value.manualAllowed);assertTrue(vm.underwater.value.canDrive)}
            val surface=compose.onNodeWithContentDescription("按住↑，松开停止主驱动")
            val under=compose.onNodeWithContentDescription("水下按住↑，松开停止")
            surface.assertIsDisplayed();under.assertIsDisplayed()
            val surfacePoint=surface.fetchSemanticsNode().boundsInRoot.center
            val underPoint=under.fetchSemanticsNode().boundsInRoot.center
            val root=compose.onRoot()
            root.performTouchInput {down(0,surfacePoint);down(1,underPoint)}
            compose.runOnIdle {
                assertEquals("FORWARD",vm.state.value.manual.direction)
                assertEquals("FORWARD",vm.underwater.value.direction)
            }
            root.performTouchInput {up(1)}
            compose.runOnIdle {
                assertEquals("FORWARD",vm.state.value.manual.direction)
                assertEquals("STOP",vm.underwater.value.direction)
            }
            root.performTouchInput {down(1,underPoint);up(0)}
            compose.runOnIdle {
                assertEquals("STOP",vm.state.value.manual.direction)
                assertEquals("FORWARD",vm.underwater.value.direction)
            }
            root.performTouchInput {cancel()}
            compose.runOnIdle {
                assertEquals("STOP",vm.state.value.manual.direction)
                assertEquals("STOP",vm.underwater.value.direction)
            }
            compose.onNodeWithText("两台手动停止").assertIsEnabled()
            compose.onNodeWithText("水面手动驾驶").performScrollTo()
            compose.onNodeWithText("水下机器人").performScrollTo()
            capture("tablet-dual-manual-1024")
            capture("tablet-dual-manual-portrait",1600,2400)
            compose.runOnIdle {vm.page(Page.TASK)}
            capture("tablet-dual-task-1024",2048,1440)
            compose.runOnIdle {vm.disconnect()}
            compose.onNodeWithText("两台手动停止").assertIsEnabled()
        } finally {compose.runOnIdle {vm.disconnect();vm.disconnectUnderwater()}}
    }

    private fun capture(name:String,width:Int?=null,height:Int?=null) {
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            if(width!=null && height!=null) repeat(4) {
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY))
                view.layout(0,0,width,height)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            }
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file=File("build/reports/screenshots/$name.png");file.parentFile?.mkdirs()
            file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
}
