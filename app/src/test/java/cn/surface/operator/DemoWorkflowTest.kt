package cn.surface.operator

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import cn.surface.operator.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w1280dp-h800dp-land-xhdpi")
class DemoWorkflowTest {
    private fun until(description:String,predicate:()->Boolean) {
        repeat(100) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            if(predicate()) return
        }
        fail("Timed out: $description")
    }
    @Test fun localizationBothTasksMappingCatalogAndRecovery() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val vm=ViewModelProvider(controller.get())[OperatorViewModel::class.java]
        try {
            vm.connect("",true)
            until("fresh map") {vm.state.value.canPose}
            assertFalse(vm.state.value.canStart)
            vm.pick(true)
            vm.candidate(Pose(1.0,1.0,0.0,vm.state.value.map!!.revision))
            vm.publishPose()
            until("localization ready") {vm.state.value.canStart}
            vm.task("start")
            until("rectangle running") {vm.state.value.busy && vm.state.value.pending.isEmpty()}
            assertFalse(vm.state.value.canStart)
            vm.task("pause")
            until("paused") {vm.state.value.fresh && vm.state.value.task.str("phase")=="PAUSED"}
            assertFalse(vm.state.value.canStart)
            vm.task("cancel")
            until("cancelled") {vm.state.value.canSelectTask}
            vm.taskKind("l");vm.task("start")
            until("L running") {vm.state.value.busy && vm.state.value.pending.isEmpty()}
            vm.task("stop")
            until("L stopped") {vm.state.value.fresh && vm.state.value.task.str("phase")=="STOPPED"}
            vm.page(Page.MAPPING);vm.mapping("start")
            until("mapping") {vm.state.value.canMapping("save")}
            vm.mapping("save","流程测试")
            until("saved and catalog refreshed") {vm.state.value.catalog.any {it.str("name")=="流程测试"} && vm.state.value.canMapping("save_end")}
            vm.mapping("save_end","流程测试结束")
            until("ended") {vm.state.value.fresh && vm.state.value.runtime.str("mode")=="idle" && vm.state.value.pending.isEmpty()}
            assertNull(vm.state.value.map)
            vm.page(Page.MAPS)
            until("catalog") {vm.state.value.pending.isEmpty() && vm.state.value.catalog.size>=5}
            vm.selectMap("演示L池");vm.mapping("preview")
            until("preview") {vm.state.value.preview!=null}
            assertEquals(100,vm.state.value.preview!!.cells[80*160+130].toInt())
            vm.closePreview();vm.mapping("load")
            until("loaded map") {vm.state.value.canPose && vm.state.value.runtime.str("current_map")=="演示L池"}
            assertFalse(vm.state.value.canStart)
            vm.selectMap("导出失败示例");vm.mapping("retry_export")
            until("recovered") {vm.state.value.selectedEntry.flag("valid") && vm.state.value.pending.isEmpty()}
            assertTrue(vm.state.value.demo)
        } finally {vm.disconnect();controller.pause().stop().destroy()}
    }
}
