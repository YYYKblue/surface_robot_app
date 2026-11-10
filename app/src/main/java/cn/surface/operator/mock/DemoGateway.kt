package cn.surface.operator.mock

import cn.surface.operator.connection.GatewayConnection
import cn.surface.operator.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.Base64
import java.util.zip.Deflater
import java.io.ByteArrayOutputStream
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
    private var boostPending=false
    private var saved=EMPTY
    private val streamSession="demo-stream-${java.util.UUID.randomUUID()}"
    private var streamSequence=0L
    private var lastDiagnosticSent=0L
    private val diagnosticSession="demo-${java.util.UUID.randomUUID()}"
    private var diagnosticId=0L
    private val diagnosticEvents=java.util.ArrayDeque<JsonObject>()
    private fun record(source:String,reason:String,details:JsonObject=EMPTY) {
        diagnosticEvents.addLast(packet("id" to ++diagnosticId,"time_unix" to System.currentTimeMillis()/1000.0,
            "source" to source,"reason" to reason,"details" to JsonObject(details+packet("simulated" to true))))
        while(diagnosticEvents.size>50) diagnosticEvents.removeFirst()
    }
    private val catalog=mutableListOf(packet("name" to mapName,"valid" to true,"saved_at" to "模拟数据"),
        packet("name" to "演示开放墙链","valid" to true,"saved_at" to "模拟数据"),
        packet("name" to "导出失败示例","valid" to false,"recoverable" to true,"reason" to "模拟栅格导出失败，可重试"))
    private data class Run(var phase:String="IDLE",var id:String="",var begin:Int=0,var edge:Int=0,
        var boost:Boolean=false,var cutoff:Boolean=false,val points:MutableList<Point> = mutableListOf())
    // Synthetic UI playback, not a model of vessel dynamics or turn clearance.
    private val demoEdges=mapOf("l" to DemoGeometry.edges("l"),"rectangle" to DemoGeometry.edges("rectangle"))
    private val runs=mapOf("rectangle" to Run(),"l" to Run())
    private val terminal=setOf("IDLE","PAUSED","STOPPED","CANCELLED","COMPLETE")
    private fun emit(p:JsonObject)=receive(p.toString())
    @Synchronized override fun connect() {
        live=true; status(true,"模拟连接")
        emit(packet("type" to "hello","protocol" to "f1-operator-v1","client_id" to "demo",
            "session_id" to streamSession,
            "capabilities" to JsonArray(listOf("task_selection","initial_pose","start_conditions","task_stop","task_tracks","mapping","map_catalog","diagnostic_history","forward_boost").map(::JsonPrimitive))))
        record("gateway","input_timeout",packet("input_age_ms" to 310,"input_timeout_ms" to 250,"had_lease" to true,"example" to "模拟历史示例，非当前故障"))
        record("bridge","ACK progress timeout",packet("ctrl_age_sec" to .02,"ack_age_sec" to .35,"last_stop_reason" to "ACK progress timeout","example" to "模拟历史示例，非当前故障"))
        record("bridge","模拟桥已就绪",packet("state" to "READY","source" to "NANO","motion_authorized" to false))
        emitMap()
        job=scope.launch { while(isActive) {
            // The manual sender is independent of Main. Publish one coherent
            // mock tick and never race its input with the simulated robot state.
            synchronized(this@DemoGateway) {
                if(!live) return@launch
                tick++; advance(); emit(telemetry())
                if(tick%5==1) emitTracks()
                emitDiagnostics()
                if(mode=="mapping" && tick%20==0) {revision++;emitMap()}
            }
            delay(100)
        } }
    }
    private fun emitMap()=emit(packet("type" to "map","runtime_session_id" to "demo-$session","map" to if(mode=="idle") JsonNull else grid()))
    private fun emitDiagnostics() {
        val events=diagnosticEvents.filter {it.long("id")>lastDiagnosticSent}
        if(events.isNotEmpty()) {
            emit(packet("type" to "diagnostics","diagnostics" to packet("session" to diagnosticSession,"events" to JsonArray(events))))
            lastDiagnosticSent=diagnosticId
        }
    }
    private fun emitTracks()=emit(packet("type" to "tracks","session_id" to streamSession,"tasks" to buildJsonObject {
        if(mode=="localization") runs.forEach { (kind,r) -> put(kind,packet("frame_id" to "map","map_id" to "demo/$mapName","run_id" to r.id,
            "segments" to JsonArray(listOf(points(r.points))),"point_count" to r.points.size,"truncated" to false)) }
    }))
    private fun grid(name:String=mapName):JsonObject=packet("frame_id" to "map","revision" to revision,"width" to 160,"height" to 100,
        "resolution" to 0.05,"origin" to packet("x" to 0,"y" to 0,"yaw" to 0),
        "encoding" to "indexed8_zlib","data_zlib_base64" to compress(ByteArray(160*100) { i ->
            val cx=i%160; val cy=i/160
            (if(cx<4 || cx>155 || cy<4 || cy>95) 2 else if(name.contains("开放") && cx>100 && cy>75) 2 else 1).toByte()
        }))
    private fun compress(pixels:ByteArray):String {
        val encoder=Deflater(1)
        return try {
            encoder.setInput(pixels);encoder.finish()
            val result=ByteArrayOutputStream();val buffer=ByteArray(4096)
            while(!encoder.finished()) {val n=encoder.deflate(buffer);result.write(buffer,0,n)}
            Base64.getEncoder().encodeToString(result.toByteArray())
        } finally {encoder.end()}
    }
    @Synchronized override fun send(value:JsonObject):Boolean {
        if(!live) return false
        val op=value.str("op"); val action=value.str("action")
        if(op=="manual_input") {
            manual=ManualIntent(value.str("direction"),value.long("speed_percent").toInt(),value.str("mode"),
                value.flag("brush_enabled"),value.long("brush_percent").toInt(),value.flag("thrust_enabled"),value.long("thrust_percent").toInt())
            owner="demo"; return true
        }
        if(op=="manual_stop") {
            if(manual.active) record("gateway",value.str("reason"),packet("input_age_ms" to 20,"input_timeout_ms" to 250,"had_lease" to true))
            manual=manual.cleared(); return true
        }
        var extra=EMPTY; var success=true; var message="模拟操作完成"
        if(op=="task") {
            owner="demo"; manual=manual.cleared()
            val run=runs.getValue(value.str("task_type"))
            if(action=="set_forward_boost") {
                val allowed=mode=="localization" && !boostPending && runs.values.all {it.phase in terminal && it.phase!="PAUSED"}
                if(!allowed) {
                    emit(packet("type" to "reply","op" to "task","action" to action,"request_id" to value.str("request_id"),
                        "success" to false,"message" to "模拟：运行中、暂停或操作待确认时不能设置提速"))
                } else {
                    boostPending=true
                    scope.launch {
                        delay(250)
                        synchronized(this@DemoGateway) { if(live) {
                            run.boost=value.flag("enabled");boostPending=false
                            emit(packet("type" to "reply","op" to "task","action" to action,"request_id" to value.str("request_id"),
                                "success" to true,"message" to "模拟：主电机提速设置已确认"))
                            emit(telemetry())
                        } }
                    }
                }
                emit(telemetry());return true
            }
            when(action) {
                "start" -> {
                    if(tick<readyAt || boostPending || runs.values.any { it.phase !in terminal || it.phase=="PAUSED" }) { success=false; message="定位未就绪、任务运行中或操作待确认" }
                    else { run.phase="ACQUIRING"; run.id="demo-${tick}"; run.begin=tick; run.edge=0; run.cutoff=false; run.points.clear() }
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
                    runs.values.forEach { it.phase="IDLE"; it.id=""; it.edge=0; it.boost=false; it.cutoff=false; it.points.clear() }
                    emit(telemetry())
                    emitMap()
                }
                "save","save_end","retry_export" -> {
                    owner="demo"
                    val base=value.str("name").ifBlank { "模拟地图_$tick" }
                    val name=if(action=="retry_export") base else if(catalog.any { it.str("name")==base }) "${base}_$tick" else base
                    catalog.removeAll { it.str("name")==name }
                    catalog.add(0,packet("name" to name,"valid" to true,"saved_at" to "刚刚 · 模拟"))
                    saved=packet("name" to name,"paths" to JsonArray(listOf("pbstream","pgm","yaml").map { JsonPrimitive("/demo/maps/$name.$it") }))
                    extra=packet("saved" to saved)
                    if(action=="save_end") { mode="idle"; session++; emitMap() }
                }
            }
            lastOperation=packet("request_id" to value.str("request_id"),"success" to success,"message" to message)
        }
        emit(JsonObject(packet("type" to "reply","op" to op,"action" to action,"request_id" to value.str("request_id"),"success" to success,"message" to message)+extra))
        emit(telemetry())
        emitTracks();emitDiagnostics()
        return true
    }
    private fun advance() {
        runs.forEach { (kind,r) ->
            if(r.phase !in terminal) {
                val elapsed=tick-r.begin
                r.phase=when { elapsed<10->"ACQUIRING"; elapsed<25->"ALIGN_START"; elapsed<40->"MOVE_START_SIDE"; elapsed<180->"FOLLOW"; else->"COMPLETE" }
                if(elapsed>=40) {
                    val oldEdge=r.edge
                    val t=((elapsed-40)/140.0).coerceIn(0.0,1.0)
                    if(kind=="l") {
                        val progress=(elapsed-40).coerceAtMost(140)
                        r.edge=(progress/30).coerceAtMost(4)
                        val step=progress-r.edge*30
                        val edge=demoEdges.getValue(kind)[r.edge]
                        val a=edge.start;val b=edge.stop
                        val fraction=(step/20.0).coerceIn(0.0,1.0)
                        x=a.x+(b.x-a.x)*fraction;y=a.y+(b.y-a.y)*fraction;yaw=atan2(b.y-a.y,b.x-a.x)
                        if(elapsed<180 && step>=20 && r.edge<4) {
                            val nextYaw=demoEdges.getValue(kind)[r.edge+1].yaw
                            val delta=atan2(sin(nextYaw-yaw),cos(nextYaw-yaw))
                            val left=delta>0
                            r.phase=when {
                                step<22->"EDGE_STOP"
                                step<24->if(left) "LEFT_EXIT_WALL" else "MOVE_INWARD"
                                step<26->if(left) "LEFT_SHIFT_TO_ENTRY" else "MOVE_INWARD"
                                step<28->if(left) "TURN_LEFT_TO_NEXT_EDGE" else "TURN_TO_NEXT_EDGE"
                                else->"APPROACH_NEXT_EDGE"
                            }
                            if(step>=26) yaw+=delta*((step-26)/4.0)
                        }
                    }
                    else {
                        val u=t*4;r.edge=u.toInt().coerceAtMost(3)
                        val edge=demoEdges.getValue(kind)[r.edge];val fraction=u-r.edge
                        x=edge.start.x+(edge.stop.x-edge.start.x)*fraction
                        y=edge.start.y+(edge.stop.y-edge.start.y)*fraction;yaw=edge.yaw
                    }
                    if(oldEdge!=r.edge) r.cutoff=false
                    if(demoEdges.getValue(kind)[r.edge].remaining(Point(x,y))<=.8) r.cutoff=true
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
                val boostEditable=r.phase in setOf("IDLE","CANCELLED","STOPPED","COMPLETE")
                val boostActive=r.boost && r.phase=="FOLLOW" && !r.cutoff
                val boostError=when {
                    mode!="localization"->"请先加载地图"
                    active.isNotEmpty() || !boostEditable || runs.values.any {it.phase=="PAUSED"}->"只能在任务开始前设置提速；暂停后请先取消任务"
                    else->""
                }
                val edges=demoEdges.getValue(kind)
                put(kind,packet("task_type" to kind,"fresh" to (mode=="localization"),"map_id" to mapId,"run_id" to r.id,
                    "phase" to r.phase,"can_start" to (ready && !boostPending && active.isEmpty() && runs.values.none {it.phase=="PAUSED"}),"output_enabled" to false,
                    "forward_boost_enabled" to r.boost,"forward_boost_active" to boostActive,"forward_boost_editable" to boostEditable,
                    "can_set_forward_boost" to (boostError.isEmpty() && !boostPending),"forward_boost_setting_error" to boostError,
                    "forward_boost_cutoff_m" to .8,"forward_boost_cutoff_reached" to r.cutoff,
                    "forward_boost_reason" to when { !r.boost->"disabled";boostActive->"active";r.phase in terminal->"inactive_task";r.cutoff->"endpoint_cutoff";else->"waiting_follow" },
                    "start_blockers" to JsonArray(if(ready) emptyList() else listOf(packet("code" to "localization","message" to "请设置初始位姿并等待定位稳定"))),
                    "start_edge_id" to "edge_0","current_edge_id" to "edge_${r.edge}","wall_fresh" to ready,"wall_valid" to ready,"center_wall_distance_m" to DemoGeometry.WALL_TARGET,
                    "wall_target_m" to DemoGeometry.WALL_TARGET,"wall_band_min_m" to DemoGeometry.WALL_MIN,"wall_band_max_m" to DemoGeometry.WALL_MAX,
                    "front_stop_m" to DemoGeometry.FRONT_STOP,"map_front_clearance_m" to edges[r.edge].remaining(Point(x,y)),
                    "route_chain_index" to if(kind=="l") 0 else null,"route_chain_count" to if(kind=="l") 2 else null,
                    "edge_count" to edges.size,"reason" to "模拟状态回放，不代表真实换边运动或空间可行性",
                    "completion_scope" to if(kind=="l") "remaining_open_route" else "rectangle_route","coverage_complete" to false,
                    "remaining_contact_intervals" to JsonArray(listOf(packet("edge_id" to "edge_0","start" to 0,"end" to .2))),
                    "completed_at_ns" to if(r.phase=="COMPLETE") System.currentTimeMillis()*1_000_000 else 0))

            }
        }
        return packet("type" to "telemetry","active_client" to owner.ifEmpty { null },"task_busy" to (active.isNotEmpty() || runs.values.any {it.phase=="PAUSED"}),"operation_pending" to boostPending,
            "stream" to packet("session" to streamSession,"sequence" to ++streamSequence,"server_queue_ms" to 0),
            "active_tasks" to JsonArray(active.map(::JsonPrimitive)),"can_set_initial_pose" to (mode=="localization" && active.isEmpty()),"tasks" to tasks,
            "state" to packet("connected" to true,"state" to "READY","source" to "NANO","armed" to true,"motion_authorized" to false,"allow_fifth_thruster" to true,"ack_age_sec" to .02,"ctrl_age_sec" to .02,"reason" to "模拟，不驱动硬件",
                "last_stop_reason" to "","command_remaining_sec" to .15,"max_tx_gap_sec" to .052,"reconnects" to 0,"rejected_commands" to 0,"owner" to "demo"),"control_fresh" to true,
            "pose" to if(mode=="idle") JsonNull else packet("x" to x,"y" to y,"yaw" to yaw),
            "localization" to packet("fresh" to (mode=="localization"),"map_id" to mapId,"ready" to ready,"initial_pose_received" to (poseAt>0),"initial_pose_stamp_ns" to poseAt,"stable_elapsed_sec" to if(ready) 1 else 0,"stable_sec" to 1,"scan_fresh" to true,"pose_fresh" to true),
            "runtime" to packet("mode" to mode,"phase" to mode.uppercase(),"session_id" to "demo-$session","current_map" to if(mode=="localization") mapName else "","busy" to false,"scan_fresh" to true,"map_available" to (mode!="idle"),"pose_fresh" to (mode!="idle"),"last_operation" to lastOperation,"last_saved" to saved,"maps_directory" to "/demo/maps","message" to "模拟数据，仅验证界面流程"))
    }
    @Synchronized override fun close() { live=false; job?.cancel() }
}
