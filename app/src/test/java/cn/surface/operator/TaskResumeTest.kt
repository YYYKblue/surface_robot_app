package cn.surface.operator

import cn.surface.operator.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun resumeState(kind:String="l",fields:JsonObject=EMPTY):OperatorState {
    val base=boostState(kind,enabled=true,phase="PAUSED",fields=JsonObject(packet("run_id" to "original-run",
        "pause_kind" to "user","paused_phase" to "MOVE_INWARD","can_resume" to true,"resume_sequence" to 2)+fields))
    return base.copy(capabilities=base.capabilities+setOf("task_resume","mapping","map_catalog","map_delete","map_unload"),
        telemetry=JsonObject(base.telemetry+packet("task_busy" to true,"active_tasks" to JsonArray(listOf(JsonPrimitive(kind))),
            "localization" to packet("map_id" to "map-a","fresh" to true,"ready" to true),
            "runtime" to packet("mode" to "localization","current_map" to "current","map_available" to true))))
}

class TaskResumeTest {
    @Test fun bothTasksResumeOnlyConfirmedUserPauseAndKeepAllOtherActionsLocked() {
        for(kind in listOf("rectangle","l")) {
            val s=resumeState(kind)
            assertTrue(s.canResumeTask);assertTrue(s.canTaskAction("resume"));assertTrue(s.canTaskAction("cancel"))
            assertFalse(s.canTaskAction("pause"));assertFalse(s.canStart);assertFalse(s.canPose);assertFalse(s.canSetForwardBoost)
            assertFalse(s.canSelectTask);assertFalse(s.copy(page=Page.MANUAL).manualAllowed)
            for(action in listOf("start","load","delete","unload")) assertFalse(s.canMapping(action))
            for(fields in listOf(packet("pause_kind" to "fault"),packet("pause_kind" to ""),packet("phase" to "ACQUIRING"),
                packet("fresh" to false),packet("run_id" to ""),packet("can_resume" to "true"),packet("can_resume" to JsonNull)))
                assertFalse(resumeState(kind,fields).canResumeTask)
        }
    }
    @Test fun resumeRequiresFreshOwnerMapAndNoPendingWork() {
        val s=resumeState()
        for(blocked in listOf(s.copy(capabilities=s.capabilities-"task_resume"),s.copy(now=900),s.copy(foreground=false),
            s.copy(clientId="other"),s.copy(picking=true),s.copy(exportingLogs=true),s.copy(mapSession="old",
                telemetry=JsonObject(s.telemetry+packet("runtime" to packet("session_id" to "new")))),
            s.copy(telemetry=JsonObject(s.telemetry+packet("localization" to packet("map_id" to "other","fresh" to true,"ready" to true)))),
            s.copy(telemetry=JsonObject(s.telemetry+packet("operation_pending" to true))),
            s.copy(telemetry=JsonObject(s.telemetry+packet("active_tasks" to JsonArray(listOf(JsonPrimitive("rectangle")))))),
            s.copy(pending=mapOf("resume" to Pending("resume",packet("op" to "task","action" to "resume","task_type" to "l"),100)))))
            assertFalse(blocked.canResumeTask)
    }
    @Test fun pausedLockDoesNotDependOnBusyFlagAndFaultCannotBeConvertedToUserPause() {
        val s=resumeState(fields=packet("pause_kind" to "fault"))
        val n=s.copy(telemetry=JsonObject(s.telemetry+packet("task_busy" to false,"active_tasks" to JsonArray(emptyList()))))
        assertTrue(n.busy);assertFalse(n.canResumeTask);assertFalse(n.canTaskAction("pause"));assertTrue(n.canTaskAction("cancel"))
        assertTrue(n.taskPhaseLabel.contains("故障停机"));assertTrue(n.resumeConditionText.contains("排除故障"))
    }
    @Test fun resumeConfirmationRequiresSameRunAndProgressNotJustPhaseChange() {
        val s=resumeState()
        val request=Pending("r",packet("op" to "task","action" to "resume","task_type" to "l","run_id" to "original-run"),100,
            oldRun="original-run",oldResumeSequence=2)
        val waiting=s.copy(pending=mapOf("r" to request))
        for(fields in listOf(EMPTY,packet("phase" to "ACQUIRING"),packet("phase" to "FOLLOW"),
            packet("resume_sequence" to 3,"run_id" to "different-run"),packet("resume_sequence" to 3,"fresh" to false),
            packet("resume_sequence" to "3"),packet("resume_sequence" to 3.0),packet("resume_sequence" to true))) {
            val next=StateReducer.telemetry(waiting,resumeState(fields=fields).telemetry,6100)
            assertEquals(1,next.pending.size);assertFalse(next.canResumeTask);assertTrue(next.canTaskAction("cancel"))
        }
        val confirmed=StateReducer.telemetry(waiting,resumeState(fields=packet("phase" to "FOLLOW","resume_sequence" to 3)).telemetry,200)
        assertTrue(confirmed.pending.isEmpty());assertEquals("original-run",confirmed.task.str("run_id"))
        assertEquals(1,StateReducer.disconnected(waiting,"lost").pending.size)
    }
    @Test fun pendingResumeCanBeCancelledAndStopAliasCanResolveCancelled() {
        val s=resumeState(fields=packet("phase" to "ACQUIRING"))
        val r=Pending("r",packet("op" to "task","action" to "resume","task_type" to "l"),100)
        assertTrue(s.copy(pending=mapOf("r" to r)).canTaskAction("cancel"))
        assertFalse(s.copy(pending=mapOf("r" to r)).canTaskAction("pause"))
        val stop=r.copy(command=packet("op" to "task","action" to "stop","task_type" to "l"))
        assertTrue(StateReducer.telemetry(s.copy(pending=mapOf("r" to stop)),resumeState(fields=packet("phase" to "CANCELLED")).telemetry,200).pending.isEmpty())
    }
    @Test fun blockersKeepSourceReasonsAndLegacyResumeStaysDisabled() {
        val s=resumeState(fields=packet("can_resume" to false,"resume_blockers" to JsonArray(listOf(packet("code" to "resume",
            "message" to "pose drift exceeds resume limits; cancel and start a new task")))))
        assertTrue(s.resumeConditionText.contains("漂移超限"));assertTrue(s.resumeConditionText.contains("pose drift exceeds"))
        val old=s.copy(capabilities=s.capabilities-"task_resume")
        assertFalse(old.canTaskAction("resume"));assertTrue(old.resumeConditionText.contains("不支持暂停续跑"))
    }
}
