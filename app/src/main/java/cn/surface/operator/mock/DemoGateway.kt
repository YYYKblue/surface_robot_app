package cn.surface.operator.mock

import cn.surface.operator.connection.GatewayConnection
import cn.surface.operator.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.math.*

/** Entirely in-process fixture. This class has no network or hardware dependencies. */
class DemoGateway(private val scope:CoroutineScope,private val receive:(String)->Unit,
                  private val status:(Boolean,String)->Unit):GatewayConnection {
    private var job:Job?=null
    private var live=false
    private var mode="localization"
    private var mapName="演示矩形池"
    private var session=1
    private var revision=1L
    private var tick=0
    private var poseAt=0L
    private var readyAt=Int.MAX_VALUE
    private var x=2.0; private var y=2.0; private var yaw=0.0
    private var manual=ManualIntent()
    private var owner=""
    private var lastOperation=EMPTY
    private var saved=EMPTY
    private val catalog=mutableListOf(packet("name" to mapName,"valid" to true,"saved_at" to "模拟数据"),
        packet("name" to "演示L池","valid" to true,"saved_at" to "模拟数据"),
        packet("name" to "导出失败示例","valid" to false,"recoverable" to true,"reason" to "模拟栅格导出失败，可重试"))
    private data class Run(var phase:String="IDLE",var id:String="",var begin:Int=0,val points:MutableList<Point> = mutableListOf())
    private val runs=mapOf("rectangle" to Run(),"l" to Run())
    private val terminal=setOf("IDLE","PAUSED","STOPPED","CANCELLED","COMPLETE")
    private fun emit(p:JsonObject)=receive(p.toString())
    override fun connect() {
        live=true; status(true,"模拟连接")
        emit(packet("type" to "hello","protocol" to "f1-operator-v1","client_id" to "demo",
            "capabilities" to JsonArray(listOf("task_selection","initial_pose","start_conditions","task_stop","task_tracks","mapping","map_catalog").map(::JsonPrimitive))))
        emit(packet("type" to "map","map" to grid()))
        job=scope.launch { while(isActive && live) { tick++; advance(); emit(telemetry()); delay(100) } }
    }
    private fun grid(name:String=mapName):JsonObject=packet("frame_id" to "map","revision" to revision,"width" to 160,"height" to 100,
        "resolution" to 0.05,"origin" to packet("x" to 0,"y" to 0,"yaw" to 0),
        "data" to JsonArray(List(160*100) { i ->
            val cx=i%160; val cy=i/160
            JsonPrimitive(if(cx<4 || cx>155 || cy<4 || cy>95) 100 else if(name.contains("L") && cx>100 && cy>55) 100 else 0)
        }))
    override fun send(value:JsonObject):Boolean {
        if(!live) return false
        val op=value.str("op"); val action=value.str("action")
        if(op=="manual_input") {
            manual=ManualIntent(value.str("direction"),value.long("speed_percent").toInt(),value.str("mode"),
                value.flag("brush_enabled"),value.long("brush_percent").toInt(),value.flag("thrust_enabled"),value.long("thrust_percent").toInt())
            owner="demo"; return true
        }
        if(op=="manual_stop") { manual=manual.cleared(); return true }
        var extra=EMPTY; var success=true; var message="模拟操作完成"
        if(op=="task") {
            owner="demo"; manual=manual.cleared()
            val run=runs.getValue(value.str("task_type"))
            when(action) {
                "start" -> {
                    if(tick<readyAt || runs.values.any { it.phase !in terminal }) { success=false; message="定位未就绪或任务运行中" }
                    else { run.phase="ACQUIRING"; run.id="demo-${tick}"; run.begin=tick; run.points.clear() }
                }
                "pause" -> run.phase="PAUSED"
                "stop" -> run.phase="STOPPED"
                "cancel" -> run.phase="CANCELLED"
            }
        } else if(op=="initial_pose") {
            owner="demo"; x=value.num("x"); y=value.num("y"); yaw=value.num("yaw")
            poseAt=System.currentTimeMillis()*1_000_000; readyAt=tick+10
        } else if(op=="mapping") {
            when(action) {
                "list" -> extra=packet("maps" to JsonArray(catalog),"maps_directory" to "/demo/maps")
                "preview" -> extra=packet("preview" to grid(value.str("name")),"name" to value.str("name"))
                "load","start","discard" -> {
                    owner="demo"; session++; revision++; readyAt=Int.MAX_VALUE; poseAt=0
                    mode=when(action) { "load"->"localization"; "start"->"mapping"; else->"idle" }
                    if(action=="load") mapName=value.str("name")
                    runs.values.forEach { it.phase="IDLE"; it.id=""; it.points.clear() }
                    emit(telemetry())
                    emit(packet("type" to "map","map" to if(mode=="idle") JsonNull else grid()))
                }
                "save","save_end","retry_export" -> {
                    owner="demo"
                    val base=value.str("name").ifBlank { "模拟地图_$tick" }
                    val name=if(action=="retry_export") base else if(catalog.any { it.str("name")==base }) "${base}_$tick" else base
                    catalog.removeAll { it.str("name")==name }
                    catalog.add(0,packet("name" to name,"valid" to true,"saved_at" to "刚刚 · 模拟"))
                    saved=packet("name" to name,"paths" to JsonArray(listOf("pbstream","pgm","yaml").map { JsonPrimitive("/demo/maps/$name.$it") }))
                    extra=packet("saved" to saved)
                    if(action=="save_end") { mode="idle"; session++; emit(packet("type" to "map","map" to JsonNull)) }
                }
            }
            lastOperation=packet("request_id" to value.str("request_id"),"success" to success,"message" to message)
        }
        emit(JsonObject(packet("type" to "reply","op" to op,"action" to action,"request_id" to value.str("request_id"),"success" to success,"message" to message)+extra))
        emit(telemetry())
        return true
    }
    private fun advance() {
        runs.forEach { (kind,r) ->
            if(r.phase !in terminal) {
                val elapsed=tick-r.begin
                r.phase=when { elapsed<10->"ACQUIRING"; elapsed<25->"ALIGN_START"; elapsed<40->"MOVE_START_SIDE"; elapsed<180->"FOLLOW"; else->"COMPLETE" }
                if(elapsed>=40) {
                    val t=((elapsed-40)/140.0).coerceIn(0.0,1.0)
                    if(kind=="l") { x=1.0+5.0*min(1.0,t*2); y=1.0+2.5*max(0.0,t*2-1); yaw=if(t<.5) 0.0 else PI/2 }
                    else { val u=t*4; when(u.toInt().coerceAtMost(3)) {
                        0->{x=1+6*u;y=1.0;yaw=0.0}; 1->{x=7.0;y=1+3*(u-1);yaw=PI/2}
                        2->{x=7-6*(u-2);y=4.0;yaw=PI};else->{x=1.0;y=4-3*(u-3);yaw=-PI/2}
                    } }
                    if(tick%2==0) r.points.add(Point(x,y))
                }
            }
        }
        val d=.025*manual.speed/25
        when(manual.direction) { "FORWARD"->x+=d*cos(yaw); "REVERSE"->x-=d*cos(yaw); "LEFT"->y+=d; "RIGHT"->y-=d }
        x=x.coerceIn(.3,7.7);y=y.coerceIn(.3,4.7)
    }
    private fun points(ps:List<Point>)=JsonArray(ps.map { JsonArray(listOf(JsonPrimitive(it.x),JsonPrimitive(it.y))) })
    private fun telemetry():JsonObject {
        val active=runs.filterValues { it.phase !in terminal }.keys
        val ready=tick>=readyAt && mode=="localization"
        val mapId="demo/$mapName"
        val tasks=buildJsonObject {
            runs.forEach { (kind,r) ->
                val path=if(kind=="l") listOf(Point(1.0,1.0),Point(6.0,1.0),Point(6.0,3.5)) else listOf(Point(1.0,1.0),Point(7.0,1.0),Point(7.0,4.0),Point(1.0,4.0),Point(1.0,1.0))
                put(kind,packet("task_type" to kind,"fresh" to (mode=="localization"),"map_id" to mapId,"run_id" to r.id,
                    "phase" to r.phase,"can_start" to (ready && active.isEmpty() && r.phase!="PAUSED"),"output_enabled" to false,
                    "start_blockers" to JsonArray(if(ready) emptyList() else listOf(packet("code" to "localization","message" to "请设置初始位姿并等待定位稳定"))),
                    "start_edge_id" to "AB","current_edge_id" to "AB","wall_fresh" to ready,"wall_valid" to ready,"center_wall_distance_m" to .62,
                    "completion_scope" to if(kind=="l") "remaining_l_route" else "rectangle_route","coverage_complete" to false,
                    "remaining_contact_intervals" to JsonArray(listOf(packet("edge_id" to "AB","start" to 0,"end" to .2))),
                    "completed_at_ns" to if(r.phase=="COMPLETE") System.currentTimeMillis()*1_000_000 else 0,
                    "expected_track" to packet("frame_id" to "map","map_id" to mapId,"cleaning_segments" to JsonArray(path.zipWithNext().map { (a,b)->packet("points" to points(listOf(a,b))) }),
                        "start_region" to packet("polygon" to points(listOf(Point(.5,.5),Point(1.5,.5),Point(1.5,1.5),Point(.5,1.5))),"reference_wall_point" to JsonArray(listOf(JsonPrimitive(1),JsonPrimitive(.2))))),
                    "actual_track" to packet("frame_id" to "map","map_id" to mapId,"run_id" to r.id,"segments" to JsonArray(listOf(points(r.points))),"point_count" to r.points.size,"truncated" to false)))
            }
        }
        return packet("type" to "telemetry","active_client" to owner.ifEmpty { null },"task_busy" to active.isNotEmpty(),"operation_pending" to false,
            "active_tasks" to JsonArray(active.map(::JsonPrimitive)),"can_set_initial_pose" to (mode=="localization" && active.isEmpty()),"tasks" to tasks,
            "state" to packet("connected" to true,"state" to "READY","source" to "NANO","armed" to true,"motion_authorized" to false,"allow_fifth_thruster" to true,"ack_age_sec" to .02,"ctrl_age_sec" to .02,"reason" to "模拟，不驱动硬件"),"control_fresh" to true,
            "pose" to if(mode=="idle") JsonNull else packet("x" to x,"y" to y,"yaw" to yaw),
            "scan" to if(mode=="idle") JsonNull else packet("frame_id" to "map","points" to points(List(80){i->Point(.25+i*.095,.25+.02*sin(i.toDouble()))})),
            "localization" to packet("fresh" to (mode=="localization"),"map_id" to mapId,"ready" to ready,"initial_pose_received" to (poseAt>0),"initial_pose_stamp_ns" to poseAt,"stable_elapsed_sec" to if(ready) 1 else 0,"stable_sec" to 1,"scan_fresh" to true,"pose_fresh" to true),
            "runtime" to packet("mode" to mode,"phase" to mode.uppercase(),"session_id" to "demo-$session","current_map" to if(mode=="localization") mapName else "","busy" to false,"scan_fresh" to true,"map_available" to (mode!="idle"),"pose_fresh" to (mode!="idle"),"last_operation" to lastOperation,"last_saved" to saved,"maps_directory" to "/demo/maps","message" to "模拟数据，仅验证界面流程"))
    }
    override fun close() { live=false; job?.cancel() }
}
