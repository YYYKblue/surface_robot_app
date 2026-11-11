package cn.surface.operator

import cn.surface.operator.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun mapManagementState():OperatorState=OperatorState(connected=true,clientId="me",receivedAt=100,now=100,page=Page.MAPS,
    capabilities=setOf("mapping","map_catalog","map_delete","map_unload"),selectedMap="saved",
    catalog=listOf(packet("name" to "saved","valid" to true)),telemetry=packet("active_client" to "me",
        "runtime" to packet("mode" to "localization","current_map" to "current","session_id" to "runtime","map_available" to true)))

class MapManagementTest {
    @Test fun deletionAndUnloadRequireCapabilitiesOwnershipFreshnessAndIdleOperations() {
        val s=mapManagementState()
        assertTrue(s.canMapping("delete"));assertTrue(s.canMapping("unload"))
        for(blocked in listOf(s.copy(capabilities=s.capabilities-setOf("map_delete","map_unload")),s.copy(now=900),
            s.copy(foreground=false),s.copy(clientId="other"),s.copy(pending=mapOf("x" to Pending("x",packet("op" to "mapping"),100))),
            s.copy(telemetry=JsonObject(s.telemetry+packet("task_busy" to true))),
            s.copy(telemetry=JsonObject(s.telemetry+packet("operation_pending" to true))),
            s.copy(telemetry=JsonObject(s.telemetry+packet("runtime" to packet("mode" to "localization","operation" to packet("request_id" to "x"))))))) {
            assertFalse(blocked.canMapping("delete"));assertFalse(blocked.canMapping("unload"))
        }
    }
    @Test fun currentMapMustBeUnloadedAndMappingMustBeEndedBeforeUnload() {
        val s=mapManagementState().copy(selectedMap="current",catalog=listOf(packet("name" to "current","valid" to true)))
        assertFalse(s.canMapping("delete"));assertTrue(s.mapDeleteBlockReason!!.contains("先卸载"))
        for(mode in listOf("mapping","idle","")) assertFalse(s.copy(telemetry=packet("runtime" to packet("mode" to mode))).canMapping("unload"))
    }
    @Test fun incompleteFilesCanBeDeletedButSnapshotsNeedExactValidIdentity() {
        val s=mapManagementState().copy(catalog=listOf(packet("name" to "saved","valid" to false)))
        assertTrue(s.canMapping("delete"));assertFalse(s.canMapping("load"))
        for(id in listOf("","abc","../unsafe","A".repeat(32))) {
            val n=s.copy(selectedSnapshotId=id,catalog=listOf(packet("name" to "saved","recoverable" to true,"snapshot_id" to id)))
            assertFalse(n.canMapping("delete"))
        }
        val snapshot=packet("name" to "saved","recoverable" to true,"snapshot_id" to "a".repeat(32))
        val both=s.copy(catalog=s.catalog+snapshot)
        assertFalse(both.selectedEntry.flag("recoverable"));assertTrue(both.canMapping("delete"))
        val selected=both.copy(selectedSnapshotId="a".repeat(32))
        assertTrue(selected.selectedEntry.flag("recoverable"));assertTrue(selected.canMapping("delete"))
        assertFalse(selected.canMapping("preview"));assertFalse(selected.canMapping("load"))
    }
    @Test fun confirmationCannotFollowSelectionChangesMissingEntriesOrReconnect() {
        val s=mapManagementState().let {it.copy(deleteTarget=it.mapSelection)}
        assertTrue(s.canConfirmMapDeletion)
        assertFalse(s.copy(selectedSnapshotId="b".repeat(32)).canConfirmMapDeletion)
        assertFalse(s.copy(catalog=emptyList()).canConfirmMapDeletion)
        assertFalse(s.copy(catalog=s.catalog+s.catalog).canConfirmMapDeletion)
        assertNull(StateReducer.disconnected(s,"lost").deleteTarget)
        assertNull(StateReducer.hello(s,packet("client_id" to "me")).deleteTarget)
    }
    @Test fun unloadTelemetryClearsCurrentMapCandidateAndTrackCache() {
        val grid=GridMap(2,2,.05,Point(0.0,0.0),0.0,1,"map",ByteArray(4))
        val s=mapManagementState().copy(map=grid,mapSession="runtime",preview=grid,picking=true,candidate=Pose(0.0,0.0,0.0,1),
            stream=StreamState(tracks=mapOf("l" to packet("run_id" to "old"))))
        val n=StateReducer.telemetry(s,packet("runtime" to packet("mode" to "idle","session_id" to "unloaded","map_available" to false)),200)
        assertNull(n.map);assertNull(n.preview);assertNull(n.candidate);assertFalse(n.picking);assertTrue(n.stream.tracks.isEmpty())
        assertNull(StateReducer.map(n,packet("runtime_session_id" to "runtime"),grid,250).map)
    }
}
