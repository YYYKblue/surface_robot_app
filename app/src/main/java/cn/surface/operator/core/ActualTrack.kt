package cn.surface.operator.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** History is only meaningful in the current map, coordinate frame and task run. */
fun actualTrackPolylines(task:JsonObject,grid:GridMap):List<List<Point>> {
    val actual=task.obj("actual_track")
    if(task.str("map_id").isEmpty() || task.str("run_id").isEmpty() ||
        actual.str("frame_id")!=grid.frame || actual.str("map_id")!=task.str("map_id") ||
        actual.str("run_id")!=task.str("run_id")) return emptyList()
    // Keep segment boundaries and invalid-point gaps; never invent connecting motion.
    return actual.arr("segments").flatMap { (it as? JsonArray)?.polylines().orEmpty() }
}
