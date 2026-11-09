package cn.surface.operator.core

import kotlinx.serialization.json.*
import java.util.Locale

fun centimeters(meters:Double):String=String.format(Locale.ROOT,"%.2f",meters*100).trimEnd('0').trimEnd('.')

data class CenterTrackOverlay(val target:Double,val lower:Double,val upper:Double,val stop:Double,val stops:List<Point>) {
    val legend get()="船中心目标距左墙 ${centimeters(target)} cm · 开刷范围 ${centimeters(lower)}–${centimeters(upper)} cm · 距墙端 ${centimeters(stop)} cm 停车"
}

/** The controller owns geometry. These are its unmodified segment endpoints,
 * not locally offset brush paths or a straight-line connection between edges. */
fun OperatorState.centerTrackOverlay(grid:GridMap?=map):CenterTrackOverlay? {
    if(grid==null || !fresh || !task.flag("fresh") || !matchingTaskMap) return null
    val expected=task.obj("expected_track")
    if(expected.str("basis")!="boat_center_stop" || expected.str("frame_id")!=grid.frame ||
        expected.str("map_id")!=task.str("map_id")) return null
    fun positive(key:String):Double?=(expected[key] as? JsonPrimitive)?.takeUnless {it.isString}
        ?.doubleOrNull?.takeIf {it.isFinite() && it>0}
    val target=positive("wall_target_m") ?: return null
    val lower=positive("wall_band_min_m") ?: return null
    val upper=positive("wall_band_max_m") ?: return null
    val stop=positive("front_stop_m") ?: return null
    if(lower>upper) return null
    val stops=expected.arr("cleaning_segments").mapNotNull { segment ->
        val points=(segment as? JsonObject)?.arr("points") ?: return@mapNotNull null
        if(points.size<2 || points.any {it.point()==null}) null else points.last().point()
    }
    return CenterTrackOverlay(target,lower,upper,stop,stops)
}
