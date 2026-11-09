package cn.surface.operator.core

import kotlinx.serialization.json.*

enum class Page(val title: String, val symbol: String) {
    TASK("清洗作业","◈"), MANUAL("手动驾驶","✥"), MAPPING("建图","▧"), MAPS("地图库","▦"), DIAGNOSTICS("诊断","≋"), SETTINGS("连接设置","⚙")
}
data class Pending(val id: String, val command: JsonObject, val sentAt: Long,
                   val oldRun: String="", val oldPoseStamp: Long=0) {
    val action get()=command.str("action")
    val readOnly get()=command.str("op")=="mapping" && action in setOf("list","preview")
}
data class OperatorState(
    val connected: Boolean=false, val connection: String="未连接", val demo: Boolean=false,
    val clientId: String="", val capabilities: Set<String> = emptySet(),
    val telemetry: JsonObject=EMPTY, val receivedAt: Long=-1, val now: Long=0,
    val map: GridMap?=null, val mapSession: String="", val preview: GridMap?=null, val previewName: String="",
    val page: Page=Page.TASK, val taskKind: String="rectangle",
    val manual: ManualIntent=ManualIntent(), val driving: Boolean=false,
    val foreground: Boolean=true, val picking: Boolean=false, val candidate: Pose?=null,
    val pending: Map<String,Pending> = emptyMap(), val catalog: List<JsonObject> = emptyList(),
    val selectedMap: String="", val message: String="请连接机器人，或进入模拟模式", val logs: List<DiagnosticEntry> = emptyList(),
    val exportingLogs:Boolean=false
) {
    val fresh get()=connected && receivedAt>=0 && now-receivedAt in 0..799
    val runtime get()=telemetry.obj("runtime")
    val task get()=telemetry.obj("tasks").obj(taskKind)
    val openRoute get()=task.str("completion_scope") in setOf("remaining_open_route","remaining_l_route")
    // Older controllers omit chain metadata. Never turn missing/invalid values into chain 1.
    val routeChainLabel: String? get() {
        if(taskKind!="l") return null
        val index=task.long("route_chain_index",-1)
        val count=task.long("route_chain_count",0)
        return if(count>0 && index>=0 && index<count) "当前墙链：${index+1} / $count" else null
    }
    val localization get()=telemetry.obj("localization")
    val operator get()=fresh && clientId.isNotEmpty() && telemetry.str("active_client").let { it.isEmpty() || it==clientId }
    val busy get()=telemetry.flag("task_busy") || telemetry.flag("operation_pending") || runtime.flag("busy")
    val taskSupported get()=capabilities.containsAll(setOf("task_selection","initial_pose","start_conditions"))
    val mappingSupported get()=capabilities.containsAll(setOf("mapping","map_catalog"))
    val manualAllowed get()=operator && foreground && page==Page.MANUAL && !picking && preview==null && !busy && pending.isEmpty()
    val canDrive get()=manualAllowed && driving
    val canSelectTask get()=operator && !busy && pending.isEmpty() && !picking
    val canPose get()=operator && foreground && taskSupported && !busy && pending.isEmpty() && map?.frame=="map" && map.revision>0 && telemetry.flag("can_set_initial_pose")
    val canStart get()=operator && foreground && taskSupported && !busy && pending.isEmpty() && !picking && task.flag("fresh") && task.flag("can_start")
    fun canTaskAction(action: String): Boolean {
        if(action !in setOf("start","pause","stop","cancel")) return false
        if(action=="start") return canStart
        if(!operator || !foreground || !taskSupported) return false
        if(action=="stop" && "task_stop" !in capabilities) return false
        if(pending.values.any { it.command.str("op")=="task" && it.action in setOf("pause","stop","cancel") && now-it.sentAt<5000 }) return false
        val active=telemetry.arr("active_tasks").any { (it as? JsonPrimitive)?.content==taskKind }
        val starting=pending.values.any { it.command.str("op")=="task" && it.action=="start" && it.command.str("task_type")==taskKind }
        val known=task.flag("fresh") && task.str("phase")=="PAUSED"
        return active || starting || known || (action=="cancel" && task.flag("fresh") && task.str("run_id").isNotEmpty() && task.str("phase") !in setOf("IDLE","CANCELLED"))
    }
    val selectedEntry get()=catalog.find { it.str("name")==selectedMap } ?: EMPTY
    fun canMapping(action: String): Boolean {
        if(!fresh || !foreground || !mappingSupported || pending.isNotEmpty()) return false
        val read=action in setOf("list","preview")
        if(read && runtime.obj("operation").isNotEmpty()) return false
        if(!read && (!operator || busy)) return false
        val mode=runtime.str("mode")
        return when(action) {
            "start" -> mode!="mapping" && runtime.flag("scan_fresh")
            "save","save_end" -> mode=="mapping" && runtime.flag("scan_fresh") && runtime.flag("map_available") && runtime.flag("pose_fresh")
            "discard" -> mode=="mapping"
            "load" -> mode!="mapping" && selectedEntry.flag("valid")
            "preview" -> selectedEntry.flag("valid")
            "retry_export" -> selectedEntry.flag("recoverable")
            "list" -> true
            else -> false
        }
    }
    val matchingTaskMap get()=localization.str("map_id").let { it.isNotEmpty() && it==task.str("map_id") }
    val wallDistance get()=task.num("center_wall_distance_m",Double.NaN).takeIf {
        fresh && task.flag("fresh") && matchingTaskMap && task.flag("wall_fresh") && task.flag("wall_valid") && it.isFinite() && it>0
    }
}

/** Called only on the UI dispatcher; all decisions also run when an action is submitted. */
object StateReducer {
    fun disconnected(s: OperatorState, reason: String)=s.copy(connected=false,connection=reason,
        clientId="",capabilities=emptySet(),receivedAt=-1,driving=false,manual=s.manual.cleared(),picking=false,candidate=null)
    fun telemetry(s: OperatorState, p: JsonObject, now: Long, receivedAt:Long=now): OperatorState {
        val oldSession=s.runtime.str("session_id"); val nextSession=p.obj("runtime").str("session_id")
        val changed=oldSession.isNotEmpty() && oldSession!=nextSession
        val oldMap=s.localization.str("map_id"); val newMap=p.obj("localization").str("map_id")
        val mapChanged=oldMap.isNotEmpty() && oldMap!=newMap
        val remaining=s.pending.filterValues { request ->
            val t=p.obj("tasks").obj(request.command.str("task_type"))
            val last=p.obj("runtime").obj("last_operation")
            val confirmed=when(request.command.str("op")) {
                "mapping" -> last.str("request_id")==request.id
                "initial_pose" -> p.obj("localization").flag("fresh") && p.obj("localization").long("initial_pose_stamp_ns")>request.oldPoseStamp
                "task" -> t.flag("fresh") && when(request.action) {
                    "start" -> t.str("run_id").isNotEmpty() && t.str("run_id")!=request.oldRun
                    "stop" -> t.str("phase")=="STOPPED"
                    "pause" -> t.str("phase")=="PAUSED"
                    "cancel" -> t.str("phase")=="CANCELLED"
                    else -> false
                }
                else -> false
            }
            !confirmed
        }
        var next=s.copy(telemetry=p,receivedAt=receivedAt,now=now,pending=remaining)
        // The gateway sends map:null + the new session before its first map. Do not
        // discard that new map when the following telemetry first reports the session.
        if((changed || mapChanged) && (nextSession.isEmpty() || s.mapSession!=nextSession) ||
            p.obj("runtime").let { it.isNotEmpty() && !it.flag("map_available") })
            next=next.copy(map=null,picking=false,candidate=null)
        if(!next.canDrive) next=next.copy(driving=false,manual=next.manual.cleared())
        if(!next.canPose) next=next.copy(picking=false,candidate=null)
        return next
    }
    fun tick(s: OperatorState, now: Long): OperatorState {
        var n=s.copy(now=now)
        if(!n.canDrive) n=n.copy(driving=false,manual=n.manual.cleared())
        if(!n.fresh) n=n.copy(picking=false,candidate=null)
        return n
    }
}

val phaseLabels=mapOf("IDLE" to "待启动","ACQUIRING" to "获取控制权","ALIGN_START" to "调整船头方向",
    "APPROACH_START" to "确认起始范围","MOVE_START_ALONG" to "前后调整","MOVE_START_SIDE" to "侧移靠近",
    "ACQUIRE_WALL" to "确认墙面","DOCK_WALL" to "靠墙对齐","FOLLOW" to "沿墙清洗",
    "CORRECT_HEADING" to "航向纠偏","WAIT_WALL" to "等待墙面观测","EDGE_STOP" to "当前边结束",
    "MOVE_INWARD" to "后退侧移","TURN_TO_NEXT_EDGE" to "转向下一面墙","APPROACH_NEXT_EDGE" to "靠近下一面墙",
    "LEFT_EXIT_WALL" to "左转换边：前行走出当前墙端",
    "LEFT_SHIFT_TO_ENTRY" to "左转换边：左移至下一墙入口",
    "TURN_LEFT_TO_NEXT_EDGE" to "左转换边：左转对齐下一墙",
    "APPROACH_EDGE" to "接近清洗边","APPROACH_WALL" to "靠墙","COMPLETE" to "本次路线执行完成",
    "PAUSED" to "已暂停 · 取消后重新开始","STOPPED" to "任务已停止","CANCELLED" to "任务已取消")
