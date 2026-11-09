package cn.surface.operator

import cn.surface.operator.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun boostState(kind:String="l",enabled:Boolean=false,phase:String="IDLE",fields:JsonObject=EMPTY):OperatorState {
    val task=JsonObject(packet("task_type" to kind,"fresh" to true,"phase" to phase,"map_id" to "map-a","can_start" to true,
        "forward_boost_enabled" to enabled,"forward_boost_editable" to true,"can_set_forward_boost" to true,
        "forward_boost_active" to false,"forward_boost_reason" to if(enabled) "inactive_task" else "disabled")+fields)
    return OperatorState(connected=true,clientId="me",receivedAt=100,now=100,taskKind=kind,
        capabilities=setOf("task_selection","initial_pose","start_conditions","task_stop","forward_boost"),
        telemetry=packet("active_client" to "me","tasks" to packet(kind to task),"localization" to packet("map_id" to "map-a")))
}

class ForwardBoostTest {
    @Test fun editingRequiresFreshConfirmedCapabilityPermissionsAndIdleController() {
        for(kind in listOf("rectangle","l")) {
            val s=boostState(kind)
            assertTrue(s.canSetForwardBoost)
            for(phase in listOf("IDLE","CANCELLED","STOPPED","COMPLETE")) assertTrue(boostState(kind,phase=phase).canSetForwardBoost)
            for(phase in listOf("PAUSED","ACQUIRING","FOLLOW","LEFT_EXIT_WALL","UNKNOWN")) assertFalse(boostState(kind,phase=phase).canSetForwardBoost)
            for(blocked in listOf(s.copy(capabilities=s.capabilities-"forward_boost"),s.copy(now=900),s.copy(foreground=false),
                s.copy(picking=true),s.copy(page=Page.MANUAL),s.copy(clientId="other"),
                s.copy(telemetry=JsonObject(s.telemetry+packet("operation_pending" to true))),
                s.copy(telemetry=JsonObject(s.telemetry+packet("localization" to packet("map_id" to "other"))))))
                assertFalse(blocked.canSetForwardBoost)
            for(field in listOf("fresh","can_set_forward_boost","forward_boost_editable"))
                assertFalse(boostState(kind,fields=packet(field to false)).canSetForwardBoost)
        }
    }
    @Test fun absentAndNonBooleanSettingsStayUnknownRatherThanOff() {
        for(value in listOf(JsonNull,JsonPrimitive("true"),JsonPrimitive(1))) {
            val s=boostState(fields=packet("forward_boost_enabled" to value))
            assertNull(s.forwardBoostEnabled);assertFalse(s.canSetForwardBoost)
        }
        assertNull(boostState().copy(capabilities=emptySet()).forwardBoostEnabled)
        assertNull(StateReducer.disconnected(boostState(enabled=true),"lost").forwardBoostEnabled)
    }
    @Test fun equalTelemetryAndTimeoutCannotAcknowledgeSettingOrEnableStart() {
        val s=boostState(enabled=true)
        val request=Pending("boost",packet("op" to "task","task_type" to "l","action" to "set_forward_boost","enabled" to true),100)
        val pending=s.copy(pending=mapOf(request.id to request))
        val next=StateReducer.telemetry(pending,s.telemetry,6100)
        assertEquals(1,next.pending.size);assertFalse(next.canStart);assertFalse(next.canSetForwardBoost)
        assertTrue(next.forwardBoostStatus.contains("结果待确认"))
        assertEquals(1,StateReducer.disconnected(next,"lost").pending.size)
    }
    @Test fun activeCommandIsDistinguishedFromPreviewAndStaleStatus() {
        val s=boostState(enabled=true,phase="FOLLOW",fields=packet("forward_boost_active" to true,"output_enabled" to false))
        assertTrue(s.forwardBoostStatus.startsWith("预览"))
        assertTrue(s.copy(demo=true).forwardBoostStatus.startsWith("模拟"))
        assertEquals("提速状态未确认，不能修改。",s.copy(now=900).forwardBoostStatus)
        for(reason in listOf("endpoint_cutoff","heading_correction","waiting_follow","future_reason")) {
            val state=boostState(enabled=true,fields=packet("forward_boost_reason" to reason))
            assertFalse(state.forwardBoostStatus.contains("指令有效"))
        }
    }
}
