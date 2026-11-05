package cn.surface.operator

import cn.surface.operator.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class CoreTest {
    private fun readyTelemetry(phase:String="IDLE",run:String="old",active:Boolean=false)=packet(
        "type" to "telemetry","active_client" to "me","task_busy" to active,"operation_pending" to false,
        "active_tasks" to JsonArray(if(active) listOf(JsonPrimitive("rectangle")) else emptyList()),
        "can_set_initial_pose" to !active,
        "tasks" to packet("rectangle" to packet("fresh" to true,"can_start" to !active,"phase" to phase,"run_id" to run,"map_id" to "map-A",
            "wall_fresh" to true,"wall_valid" to true,"center_wall_distance_m" to .6)),
        "localization" to packet("fresh" to true,"ready" to true,"map_id" to "map-A","initial_pose_stamp_ns" to 10),
        "runtime" to packet("session_id" to "s1","mode" to "localization","map_available" to true,"scan_fresh" to true,"pose_fresh" to true))
    private fun ready()=OperatorState(connected=true,clientId="me",capabilities=setOf("task_selection","initial_pose","start_conditions","task_stop","mapping","map_catalog"),
        telemetry=readyTelemetry(),receivedAt=100,now=100)
    @Test fun timeoutClearsAllManualAndRequiresExplicitRearm() {
        val s=ready().copy(page=Page.MANUAL,driving=true,manual=ManualIntent("FORWARD",brush=true,thrust=true))
        assertTrue(s.canDrive)
        val old=StateReducer.tick(s,900)
        assertFalse(old.fresh);assertFalse(old.driving);assertFalse(old.manual.active)
        val restored=StateReducer.telemetry(old,readyTelemetry(),950)
        assertTrue(restored.fresh);assertFalse(restored.canDrive)
    }
    @Test fun backgroundAndOtherOperatorCannotDriveOrStart() {
        val s=ready().copy(page=Page.MANUAL,driving=true,foreground=false)
        assertFalse(s.canDrive);assertFalse(s.canStart)
        val other=ready().copy(telemetry=JsonObject(readyTelemetry()+mapOf("active_client" to JsonPrimitive("windows"))))
        assertFalse(other.operator);assertFalse(other.canStart);assertFalse(other.canTaskAction("stop"))
    }
    @Test fun queuedTelemetryDoesNotRenewDrivingPermission() {
        val s=ready().copy(page=Page.MANUAL,driving=true,manual=ManualIntent("FORWARD"))
        val n=StateReducer.telemetry(s,readyTelemetry(),1500,200)
        assertFalse(n.fresh);assertFalse(n.driving);assertFalse(n.manual.active)
    }
    @Test fun firstTelemetryAfterMapResetKeepsNewSessionMap() {
        val grid=GridMap(2,2,.05,Point(0.0,0.0),0.0,2,"map",ByteArray(4))
        val p=JsonObject(readyTelemetry()+mapOf("runtime" to packet("session_id" to "s2","map_available" to true)))
        val s=ready().copy(map=grid,mapSession="s2")
        assertSame(grid,StateReducer.telemetry(s,p,200).map)
        assertNull(StateReducer.telemetry(s.copy(mapSession="s1"),p,200).map)
    }
    @Test fun pendingStartAllowsStopButNoSecondStart() {
        val req=Pending("r",packet("op" to "task","action" to "start","task_type" to "rectangle"),100,"old")
        val s=ready().copy(pending=mapOf("r" to req))
        assertFalse(s.canStart);assertTrue(s.canTaskAction("stop"))
        assertFalse(StateReducer.telemetry(s,readyTelemetry(),150).pending.isEmpty())
        assertTrue(StateReducer.telemetry(s,readyTelemetry("ACQUIRING","new",true),200).pending.isEmpty())
    }
    @Test fun pendingMappingCannotBeClearedByUnrelatedTelemetry() {
        val s=ready().copy(pending=mapOf("save" to Pending("save",packet("op" to "mapping","action" to "save"),100)))
        assertEquals(1,StateReducer.telemetry(s,readyTelemetry(),200).pending.size)
        val p=JsonObject(readyTelemetry()+mapOf("runtime" to packet("session_id" to "s1","map_available" to true,"last_operation" to packet("request_id" to "save"))))
        assertTrue(StateReducer.telemetry(s,p,250).pending.isEmpty())
    }
    @Test fun disconnectClearsClientAndMotionButPreservesUncertainRequest() {
        val req=Pending("r",packet("op" to "task","action" to "start"),100)
        val s=StateReducer.disconnected(ready().copy(driving=true,manual=ManualIntent("LEFT"),pending=mapOf("r" to req)),"offline")
        assertFalse(s.canStart);assertEquals("",s.clientId);assertFalse(s.manual.active);assertEquals(1,s.pending.size)
    }
    @Test fun staleWallAndMismatchedMapNeverShowDistance() {
        assertEquals(.6,ready().wallDistance!!,.0001)
        assertNull(StateReducer.tick(ready(),1000).wallDistance)
        val mismatch=ready().copy(telemetry=JsonObject(readyTelemetry()+mapOf("localization" to packet("map_id" to "map-B"))))
        assertNull(mismatch.wallDistance)
    }
    @Test fun missingCapabilitiesAndMissingFreshnessFailClosed() {
        assertFalse(ready().copy(capabilities=emptySet()).canStart)
        assertFalse(ready().copy(telemetry=EMPTY).canStart)
    }
    @Test fun readOnlyCatalogDoesNotRequireControlOwnership() {
        val s=ready().copy(telemetry=JsonObject(readyTelemetry()+mapOf("active_client" to JsonPrimitive("windows"))))
        assertTrue(s.canMapping("list"));assertFalse(s.canMapping("start"))
    }
    @Test fun pausedTaskCannotBeMistakenForResume() {
        val s=ready().copy(telemetry=readyTelemetry("PAUSED",active=true))
        assertFalse(s.canStart);assertTrue(s.canTaskAction("cancel"))
    }
    @Test fun timeoutOfStopStillPermitsExplicitSafetyRequest() {
        val p=Pending("r",packet("op" to "task","action" to "stop","task_type" to "rectangle"),0)
        val s=ready().copy(now=6000,receivedAt=6000,telemetry=readyTelemetry("FOLLOW",active=true),pending=mapOf("r" to p))
        assertTrue(s.canTaskAction("stop"))
    }
    @Test fun manualWireHasDiscreteFieldsAndDisablesAuxiliaryValues() {
        val p=ManualIntent("LEFT",75,"BACK",false,100,false,-100).encode(42)
        assertEquals("LEFT",p.str("direction"));assertEquals(75,p.long("speed_percent").toInt())
        assertEquals(0L,p.long("brush_percent"));assertEquals(0L,p.long("thrust_percent"));assertEquals(42L,p.long("seq"))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnsupportedContinuousSpeed() {ManualIntent(speed=37).encode(1)}
    @Test fun rotatedMapRoundTripIncludesZoomPanAndYFlip() {
        val g=GridMap(100,80,.05,Point(2.4,-1.3),PI/3,2,"map",ByteArray(8000))
        val tr=MapTransform(g,1000.0,600.0,2.3,Point(100.0,-70.0))
        val p=Point(3.2,.6);val q=tr.screenToWorld(tr.worldToScreen(p))!!
        assertEquals(p.x,q.x,1e-10);assertEquals(p.y,q.y,1e-10)
        assertNull(tr.screenToWorld(Point(-100000.0,-100000.0),true))
    }
    @Test fun invalidCoordinatesBreakTracksRatherThanBridgeUnknownMotion() {
        val a=wireJson.parseToJsonElement("[[1,2],[2,3],null,[4,5],[5,6]]") as JsonArray
        val lines=a.polylines();assertEquals(2,lines.size);assertEquals(2,lines[0].size)
    }
    @Test(expected=IllegalArgumentException::class) fun oversizedMapIsRejectedBeforeAllocation() {
        GridMap.parse(packet("width" to 8192,"height" to 8192,"resolution" to .05))
    }
    @Test fun validMapParsesAndBadCellFails() {
        val p=packet("width" to 2,"height" to 2,"resolution" to .05,"revision" to 7,"frame_id" to "map", "origin" to packet("x" to 0,"y" to 0,"yaw" to 0),"data" to JsonArray(listOf(-1,0,50,100).map(::JsonPrimitive)))
        val g=GridMap.parse(p);assertEquals(7L,g.revision);assertEquals(-1,g.cells[0].toInt())
        try {GridMap.parse(JsonObject(p+mapOf("data" to JsonArray(listOf(0,0,0,101).map(::JsonPrimitive)))));fail("expected invalid map") } catch(_:IllegalArgumentException) {}
    }
}
