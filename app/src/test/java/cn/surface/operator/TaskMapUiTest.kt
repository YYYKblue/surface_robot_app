package cn.surface.operator

import android.graphics.Bitmap
import android.graphics.Canvas
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
import cn.surface.operator.ui.MapsPage
import cn.surface.operator.ui.TaskPage
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
class TaskMapUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun capture(name:String,dialog:Boolean=false) {
        compose.runOnIdle {
            val view=if(dialog) org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
                else compose.activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file=File("build/reports/screenshots/$name.png");file.parentFile?.mkdirs()
            file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun userPauseFaultPauseAndLegacyHaveDistinctActionsInNarrowPanel() {
        val vm=ViewModelProvider(compose.activity)[OperatorViewModel::class.java]
        var s by mutableStateOf(resumeState("rectangle"))
        compose.runOnIdle {compose.activity.setContent {MaterialTheme {
            Column(Modifier.width(350.dp).verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {TaskPage(s,vm)}
        }}}
        compose.onNodeWithText("继续任务").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("取消任务").assertIsEnabled()
        compose.onNodeWithText("停止任务").assertDoesNotExist()
        compose.onNodeWithText("开始新任务").assertIsNotEnabled()
        compose.onNodeWithText("人工暂停 · 保留原任务").assertExists()
        capture("tablet-task-resume")
        compose.runOnIdle {s=resumeState("rectangle",packet("pause_kind" to "fault","can_resume" to false))}
        compose.onNodeWithText("继续任务").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("取消任务").assertIsEnabled()
        compose.onNodeWithText("故障停机 · 请先取消任务").assertExists()
        capture("tablet-task-fault")
        compose.runOnIdle {s=s.copy(capabilities=s.capabilities-"task_resume")}
        compose.onNodeWithText("继续任务").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("停止任务").assertExists()
    }
    @Test fun mapManagementShowsUnloadAndExactSnapshotConfirmation() {
        val vm=ViewModelProvider(compose.activity)[OperatorViewModel::class.java]
        val id="a".repeat(32)
        var s by mutableStateOf(mapManagementState().copy(catalog=listOf(packet("name" to "saved","recoverable" to true,"snapshot_id" to id)),selectedSnapshotId=id))
        compose.runOnIdle {compose.activity.setContent {MaterialTheme {
            Column(Modifier.width(700.dp).verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {MapsPage(s,vm)}
        }}}
        compose.onNodeWithText("卸载当前地图／返回待机").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("删除所选地图").performScrollTo().assertIsEnabled()
        capture("tablet-map-management")
        compose.runOnIdle {s=s.copy(deleteTarget=s.mapSelection)}
        compose.onNodeWithText("确认删除").assertIsEnabled()
        compose.onNodeWithText("仅删除此快照，不删除同名已发布地图。",substring=true).assertIsDisplayed()
        capture("tablet-map-delete-confirmation",dialog=true)
        compose.runOnIdle {s=s.copy(now=900)}
        compose.onNodeWithText("确认删除").assertIsNotEnabled()
    }
}
