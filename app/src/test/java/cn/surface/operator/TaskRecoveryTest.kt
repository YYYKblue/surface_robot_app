package cn.surface.operator

import cn.surface.operator.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskRecoveryTest {
    private val capabilities=setOf("task_selection","initial_pose","start_conditions","task_stop")
    private fun state()=OperatorState(connected=true,clientId="me",capabilities=capabilities)
    private fun task(phase:String="IDLE",fresh:Boolean=true)=packet(
        "fresh" to fresh,"phase" to phase,"run_id" to if(phase=="IDLE") "" else "run-$phase",
        "can_start" to (phase=="IDLE"),"map_id" to "map-a")
    private fun telemetry(active:List<String> = emptyList(),rectangle:JsonObject=task(),open:JsonObject=task(),allowThrust:JsonElement=JsonPrimitive(true))=packet(
        "type" to "telemetry","active_client" to "me","task_busy" to active.isNotEmpty(),
        "active_tasks" to JsonArray(active.map(::JsonPrimitive)),
        "tasks" to packet("rectangle" to rectangle,"l" to open),
        "localization" to packet("fresh" to true,"ready" to true,"map_id" to "map-a"),
        "state" to packet("allow_fifth_thruster" to allowThrust))

    @Test fun newConnectionFocusesRunningOpenTaskAndEnablesItsStop() {
        val n=StateReducer.telemetry(state(),telemetry(listOf("l"),open=task("FOLLOW")),1000)
        assertEquals("l",n.taskKind)
        assertFalse(n.canSelectTask)
        assertTrue(n.canTaskAction("stop"))
        assertTrue(n.canTaskAction("pause"))
        assertTrue(n.canTaskAction("cancel"))
        assertFalse(n.canStart)
    }

    @Test fun activeLockCanStillBeStoppedWhenControllerDetailsAreStale() {
        val n=StateReducer.telemetry(state(),telemetry(listOf("l"),open=task("FOLLOW",fresh=false)),1000)
        assertEquals("l",n.taskKind)
        assertTrue(n.canTaskAction("stop"))
        assertFalse(n.canStart)
    }

    @Test fun reconnectRestoresSinglePausedTaskWithoutPreventingLaterUserSelection() {
        val ready=StateReducer.telemetry(state(),telemetry(),1000)
        val hello=StateReducer.hello(ready,packet("client_id" to "me","capabilities" to JsonArray(capabilities.map(::JsonPrimitive))))
        val paused=telemetry(open=task("PAUSED"))
        val n=StateReducer.telemetry(hello,paused,1100)
        assertEquals("l",n.taskKind)
        assertTrue(n.canTaskAction("cancel"))
        assertFalse(n.canStart)
        assertTrue(n.canSelectTask)
        // Once restored, selecting another idle task is a deliberate choice.
        val selected=n.copy(taskKind="rectangle")
        assertEquals("rectangle",StateReducer.telemetry(selected,paused,1200).taskKind)
    }

    @Test fun staleTelemetryAndAmbiguousActiveTasksDoNotChooseAController() {
        val stale=StateReducer.telemetry(state(),telemetry(listOf("l"),open=task("FOLLOW")),2000,1000)
        assertEquals("rectangle",stale.taskKind)
        assertFalse(stale.canTaskAction("stop"))
        assertTrue(stale.restoreTaskSelection)
        for(active in listOf(listOf("unknown"),listOf("l","unknown"),listOf("rectangle","l"))) {
            val n=StateReducer.telemetry(state(),telemetry(active,open=task("FOLLOW")),1000)
            assertEquals("rectangle",n.taskKind)
            assertEquals("rectangle" in active,n.canTaskAction("stop"))
        }
        val twoPaused=StateReducer.telemetry(state(),telemetry(rectangle=task("PAUSED"),open=task("PAUSED")),1000)
        assertEquals("rectangle",twoPaused.taskKind)
        val stalePaused=StateReducer.telemetry(state(),telemetry(open=task("PAUSED",fresh=false)),1000)
        assertEquals("rectangle",stalePaused.taskKind)
    }

    @Test fun pendingRequestKeepsItsTaskTargetUntilItHasBeenResolved() {
        val pending=Pending("request",packet("op" to "task","action" to "set_forward_boost","task_type" to "rectangle"),900)
        val original=state().copy(pending=mapOf(pending.id to pending))
        val p=telemetry(listOf("l"),open=task("FOLLOW"))
        val n=StateReducer.telemetry(original,p,1000)
        assertEquals("rectangle",n.taskKind)
        assertEquals(original.pending,n.pending)
        assertFalse(n.canSelectTask)
        assertTrue(n.restoreTaskSelection)
        val resolved=StateReducer.telemetry(n.copy(pending=emptyMap()),p,1100)
        assertEquals("l",resolved.taskKind)
        assertTrue(resolved.canTaskAction("stop"))
    }

    @Test fun revokedFifthThrusterDoesNotRestartWhenPermissionReturns() {
        val ready=StateReducer.telemetry(state().copy(page=Page.MANUAL),telemetry(),1000)
        val armed=ready.copy(driving=true,manual=ManualIntent("FORWARD",brush=true,thrust=true,thrustPercent=75))
        for(permission in listOf(JsonPrimitive(false),JsonNull)) {
            val revoked=StateReducer.telemetry(armed,telemetry(allowThrust=permission),1100)
            assertTrue(revoked.canDrive)
            assertEquals("FORWARD",revoked.manual.direction)
            assertTrue(revoked.manual.brush)
            assertFalse(revoked.manual.thrust)
            assertEquals(0L,revoked.manual.encode(1).long("thrust_percent"))
            val restored=StateReducer.telemetry(revoked,telemetry(),1200)
            assertFalse(restored.manual.thrust)
            assertFalse(restored.manual.encode(2).flag("thrust_enabled"))
            assertEquals("FORWARD",restored.manual.direction)
            assertTrue(restored.manual.brush)
        }
    }
}
