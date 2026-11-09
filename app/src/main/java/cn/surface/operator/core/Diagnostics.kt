package cn.surface.operator.core

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

enum class LogSource(val label:String) { APP("App"), GATEWAY("网关"), BRIDGE("串口桥") }

data class DiagnosticEntry(
    val key:Long, val timeMillis:Long, val source:LogSource, val reason:String,
    val details:JsonObject=EMPTY, val session:String="", val eventId:Long?=null,
    val nanoTime:Boolean=false, val target:String="未连接"
) {
    val stamp:String get()=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS XXX")
        .format(Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()))
    val message:String get()=diagnosticReasons[reason]?.let {"$it ($reason)"} ?: reason
    val detailText:String get()=details.entries.joinToString(" · ") { (key,value) ->
        "${diagnosticFields[key] ?: key}：${if(value is JsonPrimitive) value.contentOrNull ?: "未知" else value.toString()}"
    }
    fun matches(sourceFilter:LogSource?,query:String):Boolean = (sourceFilter==null || source==sourceFilter) &&
        (query.isBlank() || listOf(message,details.toString(),session,eventId?.toString().orEmpty(),target)
            .any { it.contains(query,ignoreCase=true) })
    fun exportLine():String = "$stamp [${source.label}/${if(nanoTime) "Nano时间" else "平板记录时间"}] [$target] " +
        (if(eventId!=null) "session=$session id=$eventId " else "") + message.replace("\r","\\r").replace("\n","\\n") +
        if(details.isEmpty()) "" else " ${details}"
}

/** UI-thread-only, bounded event log. Logging never decides control permissions. */
class DiagnosticRecorder {
    companion object { const val MAX_ENTRIES=2000; const val MAX_SESSIONS=8 }
    private val entries=ArrayDeque<DiagnosticEntry>()
    private val cursors=linkedMapOf<String,Long>()
    private val signatures=mutableMapOf<String,Any?>()
    private var key=0L
    private var revision=0L
    private var cachedRevision=-1L
    private var cached=emptyList<DiagnosticEntry>()
    private var target="未连接"

    fun connection(target:String) { this.target=target.take(300);signatures.clear() }
    fun snapshot():List<DiagnosticEntry> {
        if(cachedRevision!=revision) {cached=entries.toList().asReversed();cachedRevision=revision}
        return cached
    }
    fun clear() { entries.clear();revision++ } // Keep cursors so the next telemetry cannot replay cleared history.
    fun append(source:LogSource,reason:String,details:JsonObject=EMPTY,timeMillis:Long=System.currentTimeMillis(),
               session:String="",eventId:Long?=null,nanoTime:Boolean=false) {
        entries.addLast(DiagnosticEntry(++key,timeMillis,source,reason.take(1000),sanitize(details),session,eventId,nanoTime,target))
        while(entries.size>MAX_ENTRIES) entries.removeFirst()
        revision++
    }
    private fun change(key:String,value:Any?,source:LogSource,reason:String,details:JsonObject=EMPTY) {
        if(!signatures.containsKey(key) || signatures[key]!=value) {
            signatures[key]=value;append(source,reason,details)
        }
    }
    fun telemetry(p:JsonObject,wallTimeMillis:Long=System.currentTimeMillis()) {
        val state=p.obj("state")
        val history=p.obj("diagnostics")
        val session=history.str("session").take(160)
        if(session.isNotEmpty()) {
            if(session !in cursors) {
                if(cursors.size>=MAX_SESSIONS) cursors.remove(cursors.keys.first())
                cursors[session]=0
                append(LogSource.GATEWAY,"接收网关最近诊断记录",packet("session" to session),wallTimeMillis)
            }
            var cursor=cursors.getValue(session)
            val events=history.arr("events").takeLast(50).mapNotNull {it as? JsonObject}
                .filter {it.long("id")>0}.sortedBy {it.long("id")}
            for(e in events) {
                val id=e.long("id")
                if(id<=cursor) continue
                if(id-cursor>1) append(LogSource.GATEWAY,"部分诊断记录已被网关覆盖，无法补读",
                    packet("session" to session,"missing_events" to id-cursor-1),wallTimeMillis)
                val seconds=e.num("time_unix",Double.NaN)
                val validTime=seconds.isFinite() && seconds>0 && seconds<253402300799.0
                val details=if(validTime) e.obj("details") else JsonObject(e.obj("details")+packet("time_note" to "Nano 时间无效，使用平板接收时间"))
                append(if(e.str("source")=="bridge") LogSource.BRIDGE else LogSource.GATEWAY,
                    e.str("reason","未知诊断事件"),details,if(validTime) (seconds*1000).toLong() else wallTimeMillis,
                    session,id,validTime)
                cursor=id
            }
            cursors[session]=cursor
        } else if(state.isNotEmpty()) {
            val keys=listOf("connection_id","state","source","owner","motion_authorized","reason","last_stop_reason")
            change("bridge",keys.map {state[it]},LogSource.BRIDGE,state.str("reason","桥状态更新"),
                JsonObject(state.filterKeys {it in diagnosticFields.keys || it=="state" || it=="source" || it=="owner"}))
        }
        change("control_fresh",p["control_fresh"],LogSource.GATEWAY,"桥状态新鲜度变化",packet("control_fresh" to p["control_fresh"]))
    }
    fun observeApp(s:OperatorState) {
        change("telemetry_fresh",s.fresh,LogSource.APP,if(s.fresh) "遥测已恢复" else "遥测未收到或超过 800 ms",
            packet("telemetry_age_ms" to if(s.receivedAt>=0) (s.now-s.receivedAt).coerceAtLeast(0) else null))
        if(s.page==Page.MANUAL || s.driving) {
            val reasons=buildList {
                if(!s.fresh) add("遥测未收到或超过 800 ms")
                if(s.fresh && !s.operator) add("其他操作端占用")
                if(!s.foreground) add("App 不在前台")
                if(s.busy) add("自动任务或建图操作占用")
                if(s.pending.isNotEmpty()) add("操作等待确认")
                if(s.picking) add("正在选择初始位姿")
                if(s.preview!=null) add("正在预览地图")
                if(!s.driving) add("尚未启用手动驾驶")
            }
            change("manual_lock",reasons,LogSource.APP,if(reasons.isEmpty()) "手动驾驶已使能" else "手动驾驶已锁定",
                packet("reasons" to JsonArray(reasons.map(::JsonPrimitive))))
        }
    }
    fun sendTick(gapMillis:Long,active:Boolean) {
        if(!active) {signatures.remove("send_delay");return}
        val delayed=gapMillis>=200
        if(delayed || signatures.containsKey("send_delay")) change("send_delay",delayed,LogSource.APP,
            if(delayed) "手动发送定时器延迟" else "手动发送定时器恢复",
            packet("interval_ms" to gapMillis,"expected_ms" to 50))
    }
    private fun sanitize(input:JsonObject):JsonObject {
        fun value(e:JsonElement,depth:Int):JsonElement=when {
            depth>3 -> JsonPrimitive("[深层内容已省略]")
            e is JsonObject -> JsonObject(e.entries.take(40).associate {(k,v)-> k.take(120) to
                if(k.contains("token",true) || k.contains("password",true)) JsonPrimitive("[已隐藏]") else value(v,depth+1)})
            e is JsonArray -> JsonArray(e.take(40).map {value(it,depth+1)})
            e is JsonPrimitive && e.isString -> JsonPrimitive(e.content.take(1000))
            else -> e
        }
        val safe=value(input,0) as JsonObject
        return if(safe.toString().length<=8000) safe else packet("truncated_details" to safe.toString().take(7600),"truncated" to true)
    }
}

fun diagnosticReport(s:OperatorState,version:String):String=buildString {
    appendLine("SurfaceOperator $version 诊断日志")
    appendLine("导出时间：${java.time.OffsetDateTime.now()}")
    appendLine("连接：${s.connection}；遥测：${if(s.fresh) "新鲜" else "已过期/未收到"}")
    appendLine("当前模式：${if(s.demo) "模拟，不连接硬件" else "机器人连接"}")
    appendLine("Nano 事件采用 Nano 时间，转换为平板时区；平板与 Nano 时钟可能存在偏差。按接收顺序排列。")
    appendLine("日志仅保留本次 App 进程内最近 ${DiagnosticRecorder.MAX_ENTRIES} 条；网关仅补发最近 50 条。")
    appendLine("记录数：${s.logs.size}；协商能力：${s.capabilities.sorted().joinToString()}")
    appendLine("最近收到的桥状态（可能已过期）："+JsonObject(s.telemetry.obj("state").filterKeys {it in diagnosticFields.keys}))
    appendLine()
    s.logs.asReversed().forEach {appendLine(it.exportLine())}
}

val diagnosticReasons=mapOf(
    "input_timeout" to "网关接收手动输入超时", "app_disconnected" to "App 与网关连接断开",
    "text_input" to "输入框获得焦点，停止手动输出", "window_deactivated" to "窗口失去激活，停止手动输出",
    "window_closed" to "App 窗口关闭", "button" to "点击停止手动输出", "keyboard" to "按空格停止手动输出",
    "automatic_task_active" to "自动任务占用控制", "gateway_shutdown" to "网关退出",
    "command deadline expired" to "串口桥执行指令过期", "CTRL timeout" to "下位机 CTRL 状态超时",
    "ACK progress timeout" to "下位机 ACK 确认超时", "transmit scheduling gap" to "串口发送调度间隔超限",
    "source/epoch changed" to "控制源或控制代次发生变化", "owner released control" to "控制者释放控制权",
    "RC has control; Nano stop cannot override RC" to "遥控器 RC 持有控制权",
    "touch_cancelled" to "触摸取消，停止手动输出", "app_not_foreground" to "App 退后台，停止手动输出",
    "window_focus_lost" to "窗口失焦，停止手动输出", "page_changed" to "切换页面，停止手动输出",
    "operator" to "操作员请求停止手动输出", "disconnect" to "主动断开连接",
    "switch_connection" to "切换连接", "app_closed" to "App 关闭", "send_not_ready" to "发送未就绪，停止手动输出",
    "control_gate_changed" to "控制条件变化，停止手动输出", "telemetry_or_permission_expired" to "遥测或控制许可失效",
    "initial_pose_edit" to "编辑初始位姿", "discrete_operation" to "提交操作，停止手动输出")

val diagnosticFields=mapOf("input_age_ms" to "输入年龄(ms)","input_timeout_ms" to "输入超时阈值(ms)",
    "had_lease" to "停止前持有租约","ctrl_age_sec" to "CTRL 年龄(s)","ack_age_sec" to "ACK 年龄(s)",
    "command_remaining_sec" to "指令剩余(s)","max_tx_gap_sec" to "最大发送间隔(s)","reconnects" to "重连次数",
    "rejected_commands" to "拒绝指令数","last_stop_reason" to "最近撤销原因","motion_authorized" to "输出许可",
    "state" to "桥状态","source" to "控制源","owner" to "控制者","control_fresh" to "桥状态新鲜",
    "missing_events" to "缺失事件数","telemetry_age_ms" to "遥测年龄(ms)","interval_ms" to "实际间隔(ms)",
    "expected_ms" to "预期间隔(ms)","reasons" to "锁定原因")
