package cn.surface.operator.mock

import cn.surface.operator.core.Point
import kotlin.math.*

/** Synthetic walls only. Live paths always come from the controller. */
object DemoGeometry {
    const val WALL_TARGET=.33
    const val WALL_MIN=.32
    const val WALL_MAX=.36
    const val FRONT_STOP=.40
    const val CONTACT_START=.30
    const val BRUSH_X=.10
    val openWalls=listOf(Point(1.0,1.0),Point(3.0,1.0),Point(3.0,2.0),Point(5.0,2.0),Point(5.0,3.5),Point(7.0,3.5))
    val rectangleWalls=listOf(Point(.2,4.8),Point(7.8,4.8),Point(7.8,.2),Point(.2,.2),Point(.2,4.8))
    data class Edge(val wallStart:Point,val wallEnd:Point) {
        val length=hypot(wallEnd.x-wallStart.x,wallEnd.y-wallStart.y)
        val tx=(wallEnd.x-wallStart.x)/length
        val ty=(wallEnd.y-wallStart.y)/length
        val yaw=atan2(ty,tx)
        fun center(along:Double)=Point(wallStart.x+tx*along+ty*WALL_TARGET,wallStart.y+ty*along-tx*WALL_TARGET)
        val start=center(CONTACT_START-BRUSH_X)
        val stop=center(length-FRONT_STOP)
        fun remaining(p:Point)=length-((p.x-wallStart.x)*tx+(p.y-wallStart.y)*ty)
    }
    fun edges(kind:String)=(if(kind=="l") openWalls else rectangleWalls).zipWithNext().map {(a,b)->Edge(a,b)}
}
