package cn.surface.operator.core

import kotlinx.serialization.json.JsonObject

/** Names alone do not identify retained snapshots or distinguish them from published maps. */
data class MapSelection(val name:String,val snapshotId:String="")
val JsonObject.mapSelection get()=MapSelection(str("name"),str("snapshot_id"))
val OperatorState.mapSelection get()=MapSelection(selectedMap,selectedSnapshotId)
val OperatorState.canConfirmMapDeletion get()=deleteTarget!=null && deleteTarget==mapSelection && canMapping("delete")

val OperatorState.mapDeleteBlockReason:String? get()=when {
    "map_delete" !in capabilities -> "删除功能需要新版受管网关。"
    selectedEntry.isEmpty() || selectedMap.isBlank() -> "请先选择要删除的地图或快照。"
    selectedMap==runtime.str("current_map") -> "当前地图需先卸载，再删除。"
    selectedEntry.flag("recoverable") && !selectedSnapshotId.matches(Regex("[0-9a-f]{32}")) ->
        "快照编号缺失或无效，请升级网关并刷新列表。"
    selectedSnapshotId.isNotEmpty() && !selectedSnapshotId.matches(Regex("[0-9a-f]{32}")) -> "快照编号无效，请刷新列表。"
    else -> null
}
