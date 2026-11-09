package cn.surface.operator

import cn.surface.operator.core.*
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test

class StartConditionsTest {
    private fun state(taskFresh:Boolean=true,blockers:JsonArray=JsonArray(emptyList()))=OperatorState(
        connected=true,clientId="me",receivedAt=100,now=100,
        capabilities=setOf("task_selection","initial_pose","start_conditions"),
        telemetry=packet("localization" to packet("fresh" to true,"ready" to true),
            "tasks" to packet("rectangle" to packet("fresh" to taskFresh,"can_start" to false,"start_blockers" to blockers))))

    @Test fun readyLocalizationDoesNotHideTaskSpecificPoseFailuresOrGrantStart() {
        for((raw,expected) in mapOf(
            "pose receive stream stopped" to "控制器未持续收到新位姿",
            "fresh fixed-map base_link pose required" to "控制器缺少可用的固定地图船体位姿",
            "pose stale or future stamped" to "控制器位姿时间戳过旧或位于未来")) {
            val s=state(blockers=JsonArray(listOf(packet("code" to "feedback","message" to raw))))
            assertFalse(s.canStart)
            assertEquals("$expected\n原文：$raw",s.startConditionText)
        }
    }
    @Test fun absentControllerKeepsFreshGatewayReasonWhileStaleTransportHidesIt() {
        val s=state(false,JsonArray(listOf(packet("code" to "task_stale","message" to "当前地图无法推导开放墙链"))))
        assertTrue(s.startConditionText.startsWith("任务状态未知或过期；以下为网关阻断及控制器上次报告："))
        assertTrue(s.startConditionText.contains("任务不可用或状态过期\n原文：当前地图无法推导开放墙链"))
        assertFalse(s.canStart)
        assertEquals("所选任务状态未知或过期，等待控制器更新",state(false).startConditionText)
        assertEquals("连接中断或遥测过期，禁止启动",s.copy(now=900).startConditionText)
    }
    @Test fun unknownReasonsAndCodesRemainVisibleWithoutInventingCause() {
        assertEquals("启动条件未满足\n原文：future reason",startBlockerText(packet("code" to "new_code","message" to "future reason")))
        assertEquals("启动条件未满足\n代码：new_code",startBlockerText(packet("code" to "new_code")))
        assertEquals("反馈数据未就绪\n原文：sensor unavailable",startBlockerText(packet("code" to "feedback","message" to "sensor unavailable")))
    }
}
