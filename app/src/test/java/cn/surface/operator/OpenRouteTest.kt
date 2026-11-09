package cn.surface.operator

import cn.surface.operator.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OpenRouteTest {
    private fun state(task:JsonObject)=OperatorState(connected=true,clientId="me",receivedAt=100,now=100,
        taskKind="l",capabilities=setOf("task_selection","initial_pose","start_conditions","task_stop"),
        telemetry=packet("active_client" to "me","task_busy" to true,
            "active_tasks" to JsonArray(listOf(JsonPrimitive("l"))),"tasks" to packet("l" to task)))

    @Test fun leftTransfersKeepTaskControlsAndLockManualUntilStopConfirmed() {
        for(phase in listOf("LEFT_EXIT_WALL","LEFT_SHIFT_TO_ENTRY","TURN_LEFT_TO_NEXT_EDGE")) {
            val task=packet("phase" to phase,"fresh" to true,"run_id" to "open-1","can_start" to false)
            val s=state(task).copy(page=Page.MANUAL,driving=true,manual=ManualIntent("FORWARD"))
            assertFalse(s.canStart);assertFalse(s.canDrive)
            for(action in listOf("stop","pause","cancel")) assertTrue(s.canTaskAction(action))
            val pending=s.copy(pending=mapOf("stop" to Pending("stop",packet("op" to "task","task_type" to "l","action" to "stop"),100)))
            val running=StateReducer.telemetry(pending,s.telemetry,150)
            assertEquals(1,running.pending.size);assertFalse(running.manual.active)
            val stopped=packet("active_client" to "me","task_busy" to false,"active_tasks" to JsonArray(emptyList()),
                "tasks" to packet("l" to packet("phase" to "STOPPED","fresh" to true,"run_id" to "open-1")))
            assertTrue(StateReducer.telemetry(running,stopped,200).pending.isEmpty())
        }
    }

    @Test fun oldScopeAndAbsentOrInvalidChainMetadataRemainCompatible() {
        for(scope in listOf("remaining_l_route","remaining_open_route")) {
            val s=state(packet("completion_scope" to scope,"route_chain_index" to 0,"route_chain_count" to 2))
            assertTrue(s.openRoute);assertEquals("当前墙链：1 / 2",s.routeChainLabel)
        }
        assertFalse(state(packet("completion_scope" to "rectangle_route")).openRoute)
        for(task in listOf(EMPTY,packet("route_chain_count" to 2),packet("route_chain_index" to -1,"route_chain_count" to 2),
            packet("route_chain_index" to 2,"route_chain_count" to 2),packet("route_chain_index" to null,"route_chain_count" to null)))
            assertNull(state(task).routeChainLabel)
    }
}
