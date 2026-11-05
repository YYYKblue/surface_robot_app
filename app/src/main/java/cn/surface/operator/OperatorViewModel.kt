package cn.surface.operator

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.surface.operator.connection.*
import cn.surface.operator.core.*
import cn.surface.operator.mock.DemoGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.util.UUID

class OperatorViewModel(application: Application):AndroidViewModel(application) {
    private val mutable=MutableStateFlow(OperatorState())
    val state=mutable.asStateFlow()
    private val preferences=application.getSharedPreferences("connection",0)
    val savedUrl get()=preferences.getString("url","ws://192.168.1.188:8765")!!
    private var connection:GatewayConnection?=null
    @Volatile private var generation=0
    private var sequence=0L
    private var wanted=false
    private var retry:Job?=null
    private var url=savedUrl
    private var refreshCatalog=false
    private data class Incoming(val epoch:Int,val raw:String,val receivedAt:Long)
    private val incoming=Channel<Incoming>(16)
    init {
        viewModelScope.launch {
            for((epoch,raw,receivedAt) in incoming) {
                if(epoch!=generation) continue
                try {
                    val (p,grid)=withContext(Dispatchers.Default) {
                        val p=wireJson.parseToJsonElement(raw) as? JsonObject ?: error("消息不是对象")
                        val g=when(p.str("type")) {
                            "map" -> (if("map" in p) p["map"] else p).let { if(it is JsonObject) GridMap.parse(it) else null }
                            "reply" -> (p["preview"] as? JsonObject)?.let(GridMap::parse)
                            else -> null
                        }
                        (if(g!=null) JsonObject(p.filterKeys { it!="map" && it!="preview" && it!="data" }) else p) to g
                    }
                    if(epoch==generation) receive(p,grid,receivedAt)
                } catch(e:Exception) {
                    if(e is CancellationException) throw e
                    if(epoch==generation) { log("接收数据无效：${e.message}"); connection?.close(); lost("协议数据无效，已断开") }
                }
            }
        }
        viewModelScope.launch {
            while(isActive) {
                delay(50)
                val old=mutable.value
                val next=StateReducer.tick(old,SystemClock.elapsedRealtime())
                mutable.value=next
                if(old.driving && !next.driving) {
                    connection?.send(packet("op" to "manual_stop","reason" to "telemetry_or_permission_expired"))
                    log("控制状态已失效，手动已停用；恢复后需重新启用")
                }
                if(next.canDrive && next.manual.active) {
                    if(connection?.send(next.manual.encode(++sequence))!=true) stopManual("send_not_ready")
                }
            }
        }
    }
    private fun log(message:String) {
        val s=mutable.value
        mutable.value=s.copy(message=message,logs=(listOf("${java.time.LocalTime.now().withNano(0)}  $message")+s.logs).take(160))
    }
    fun notice(message:String)=log(message)
    fun connect(address:String, demo:Boolean=false) {
        stopManual("switch_connection"); connection?.close(); retry?.cancel(); generation++
        wanted=true; url=address.trim()
        if(!demo) {
            if(!(url.startsWith("ws://")||url.startsWith("wss://"))) {
                wanted=false; mutable.value=StateReducer.disconnected(mutable.value,"地址无效")
                log("地址必须以 ws:// 或 wss:// 开头"); return
            }
            preferences.edit().putString("url",url).apply()
        }
        // Explicitly choosing a new target starts a separate UI session.
        mutable.value=OperatorState(demo=demo,page=Page.TASK,foreground=mutable.value.foreground,connection="连接中…")
        open()
    }
    private fun open() {
        if(!wanted || !mutable.value.foreground) return
        connection?.close()
        val epoch=++generation
        val packetCallback:(String)->Unit={raw ->
            if(epoch==generation && !incoming.trySend(Incoming(epoch,raw,SystemClock.elapsedRealtime())).isSuccess) viewModelScope.launch {
                if(epoch==generation) { connection?.close(); lost("接收积压，重新连接以同步最新状态") }
            }
        }
        val statusCallback:(Boolean,String)->Unit={ok,message -> viewModelScope.launch {
            if(epoch==generation) {
                if(ok) { mutable.value=mutable.value.copy(connection=message); log(message) }
                else lost(message)
            }
        }}
        try {
            connection=if(mutable.value.demo) DemoGateway(viewModelScope,packetCallback,statusCallback)
                       else RealGateway(url,packetCallback,statusCallback)
            connection?.connect()
        } catch(e:Exception) { lost(e.message ?: "连接失败") }
    }
    private fun lost(reason:String) {
        generation++ // Drop queued packets from the dead connection, including decoded maps.
        mutable.value=StateReducer.disconnected(mutable.value,reason).let { it.copy(pending=it.pending.filterValues {request->!request.readOnly}) }
        log("$reason；旧指令不会重发")
        retry?.cancel()
        if(wanted && mutable.value.foreground) retry=viewModelScope.launch { delay(1500); open() }
    }
    fun disconnect() {
        wanted=false; retry?.cancel(); stopManual("disconnect"); connection?.close(); generation++
        mutable.value=StateReducer.disconnected(mutable.value,"已断开")
    }
    fun foreground(value:Boolean) {
        if(!value) stopManual("app_not_foreground")
        mutable.value=mutable.value.copy(foreground=value,picking=false,candidate=null)
        // Keep an existing link for state observation. No background motion sender.
        if(value && wanted && !mutable.value.connected) { retry?.cancel(); open() }
    }
    fun focusLost()=stopManual("window_focus_lost")
    fun page(value:Page) {
        stopManual("page_changed")
        mutable.value=mutable.value.copy(page=value,picking=false,candidate=null)
        if(value==Page.MAPS && mutable.value.canMapping("list")) mapping("list")
    }
    fun taskKind(value:String) {
        val s=mutable.value
        if(s.canSelectTask && value in setOf("rectangle","l")) mutable.value=s.copy(taskKind=value,candidate=null)
    }
    fun armManual() {
        val s=mutable.value
        if(s.manualAllowed) { mutable.value=s.copy(driving=true,manual=s.manual.cleared()); log("手动已使能；按住方向键运行，松开仅停止主驱动") }
    }
    fun manual(intent:ManualIntent) {
        val s=mutable.value
        if(!s.canDrive) return
        val m=if(!s.telemetry.obj("state").flag("allow_fifth_thruster")) intent.copy(thrust=false) else intent
        mutable.value=s.copy(manual=m)
        if(connection?.send(m.encode(++sequence))!=true) stopManual("send_not_ready")
    }
    fun direction(value:String)=manual(mutable.value.manual.copy(direction=value))
    fun stopManual(reason:String="operator") {
        val s=mutable.value
        // An observing client must not seize the gateway with an unsolicited STOP.
        if(s.driving || s.manual.active) connection?.send(packet("op" to "manual_stop","reason" to reason))
        mutable.value=s.copy(driving=false,manual=s.manual.cleared())
        if(s.driving || s.manual.active) log("手动已停用，全部手动输出已请求归零；需重新启用驾驶")
    }
    fun explicitStop() {
        val s=mutable.value
        if(s.operator) connection?.send(packet("op" to "manual_stop","reason" to "operator"))
        mutable.value=s.copy(driving=false,manual=s.manual.cleared())
        log("已请求停止手动输出；自动任务需使用停止任务")
    }
    fun pick(enabled:Boolean) {
        stopManual("initial_pose_edit")
        if(!enabled || mutable.value.canPose) mutable.value=mutable.value.copy(picking=enabled,candidate=null)
    }
    fun candidate(pose:Pose?) {
        val s=mutable.value
        if(s.picking && s.canPose && (pose==null || pose.revision==s.map?.revision)) mutable.value=s.copy(candidate=pose)
    }
    fun publishPose() {
        val s=mutable.value; val p=s.candidate ?: return
        if(!s.canPose || p.revision!=s.map?.revision || p.revision<1) return
        submit(packet("op" to "initial_pose","x" to p.x,"y" to p.y,"yaw" to p.yaw,"map_revision" to p.revision))
        mutable.value=mutable.value.copy(picking=false,candidate=null)
    }
    fun task(action:String) {
        if(mutable.value.canTaskAction(action)) submit(packet("op" to "task","action" to action,"task_type" to mutable.value.taskKind))
    }
    fun selectMap(name:String) { mutable.value=mutable.value.copy(selectedMap=name) }
    fun mapping(action:String,name:String="",confirmed:Boolean=false) {
        val s=mutable.value
        if(!s.canMapping(action) || (action=="discard" && !confirmed)) return
        val fields=mutableMapOf<String,JsonElement>("op" to JsonPrimitive("mapping"),"action" to JsonPrimitive(action))
        if(action in setOf("save","save_end")) {
            val n=name.trim()
            if(n.codePointCount(0,n.length)>80 || n.any { !it.isLetterOrDigit() && it!='_' && it!='-' }) { log("地图名限 80 字符：中文、字母、数字、下划线、短横线"); return }
            fields["name"]=JsonPrimitive(n)
        }
        if(action in setOf("preview","load","retry_export")) fields["name"]=JsonPrimitive(s.selectedMap)
        if(action=="discard") fields["confirm"]=JsonPrimitive(true)
        submit(JsonObject(fields))
    }
    private fun submit(base:JsonObject) {
        stopManual("discrete_operation")
        val s=mutable.value; val id=UUID.randomUUID().toString()
        val command=JsonObject(base+mapOf("request_id" to JsonPrimitive(id)))
        val pending=Pending(id,command,SystemClock.elapsedRealtime(),s.task.str("run_id"),s.localization.long("initial_pose_stamp_ns"))
        mutable.value=s.copy(pending=s.pending+(id to pending))
        if(connection?.send(command)!=true) {
            mutable.value=mutable.value.copy(pending=mutable.value.pending-id)
            log("未发送：连接不可用")
        } else log("操作已发送，等待机器人确认：${base.str("action",base.str("op"))}")
    }
    fun clearUncertain() {
        val s=mutable.value
        if(s.fresh && !s.busy && s.pending.isNotEmpty() && s.pending.values.all { s.now-it.sentAt>5000 }) {
            mutable.value=s.copy(pending=emptyMap()); log("已清除本地待确认提示；未重发任何操作，请以机器人状态为准")
        }
    }
    fun closePreview() { mutable.value=mutable.value.copy(preview=null) }
    private fun receive(p:JsonObject,grid:GridMap?,receivedAt:Long) {
        val s=mutable.value
        when(p.str("type")) {
            "hello" -> {
                if(p.str("protocol")!="f1-operator-v1") { wanted=false; connection?.close(); lost("网关协议不兼容"); return }
                mutable.value=s.copy(connected=true,connection=if(s.demo) "模拟模式 · 不连接硬件" else "已连接",
                    clientId=p.str("client_id"),capabilities=p.arr("capabilities").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet(),
                    map=null,mapSession="",telemetry=EMPTY,receivedAt=-1,picking=false,candidate=null)
                log(if(s.demo) "模拟网关已就绪，不连接硬件" else "握手成功，等待新遥测")
                refreshCatalog=s.page==Page.MAPS
            }
            "map" -> mutable.value=s.copy(map=grid,mapSession=p.str("session_id").ifEmpty {s.mapSession.ifEmpty {s.runtime.str("session_id")}},candidate=null,picking=false)
            "telemetry" -> {
                if(!s.connected) return
                val n=StateReducer.telemetry(s,p,SystemClock.elapsedRealtime(),receivedAt)
                mutable.value=n
                if(s.driving && !n.driving) { connection?.send(packet("op" to "manual_stop","reason" to "control_gate_changed")) }
                val saved=n.runtime.obj("last_saved")
                if(saved.isNotEmpty() && saved!=s.runtime.obj("last_saved")) {
                    mutable.value=mutable.value.copy(selectedMap=saved.str("name"))
                    refreshCatalog=true
                }
                if(refreshCatalog && mutable.value.canMapping("list")) { refreshCatalog=false;mapping("list") }
            }
            "reply" -> {
                val id=p.str("request_id")
                if(!p.flag("pending")) {
                    val request=s.pending[id]
                    // A successful service reply must not leave pre-operation readiness usable.
                    mutable.value=s.copy(pending=s.pending-id,receivedAt=if(p.flag("success") && request!=null && !request.readOnly) -1 else s.receivedAt)
                }
                log(p.str("message",if(p.flag("success")) "请求已处理，请查看机器人状态" else "请求被拒绝"))
                if(p.flag("pending") || !p.flag("success")) return
                if("maps" in p) {
                    val entries=p.arr("maps").mapNotNull { it as? JsonObject }
                    mutable.value=mutable.value.copy(catalog=entries,selectedMap=mutable.value.selectedMap.takeIf { n -> entries.any { it.str("name")==n } } ?: entries.firstOrNull()?.str("name").orEmpty())
                }
                if(grid!=null) { stopManual("map_preview"); mutable.value=mutable.value.copy(preview=grid,previewName=p.str("name")) }
                val saved=p.obj("saved")
                if(saved.isNotEmpty()) {
                    mutable.value=mutable.value.copy(selectedMap=saved.str("name"))
                    log("已保存：${saved.str("name")}\n${saved.arr("paths").joinToString("\n") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }}")
                    refreshCatalog=true
                }
            }
            "error" -> { stopManual("gateway_error"); log("网关错误：${p.str("message")}") }
        }
    }
    override fun onCleared() { wanted=false; stopManual("app_closed"); connection?.close(); incoming.close() }
}
