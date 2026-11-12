package cn.surface.operator.core

import kotlinx.serialization.json.*

const val UNDERWATER_PROTOCOL = "under-operator-v1"
const val UNDERWATER_DEVICE = "h7_under_original_v6"
val UNDERWATER_DIRECTIONS = setOf("STOP", "FORWARD", "REVERSE", "LEFT", "RIGHT")

fun underwaterUrl(surfaceUrl:String):String {
    val source=java.net.URI(surfaceUrl.trim())
    require(source.scheme in setOf("ws","wss") && !source.host.isNullOrBlank()) {"地址必须是有效的 ws:// 或 wss:// 地址"}
    return java.net.URI(source.scheme,null,source.host,8766,null,null,null).toASCIIString()
}

fun underwaterPacket(vararg values:Pair<String,Any?>):JsonObject = packet(
    "protocol" to UNDERWATER_PROTOCOL, "device" to UNDERWATER_DEVICE, *values)

internal fun JsonObject.underLong(key:String):Long? =
    (this[key] as? JsonPrimitive)?.takeUnless {it.isString}?.longOrNull
internal fun JsonObject.underText(key:String):String? =
    (this[key] as? JsonPrimitive)?.takeIf {it.isString}?.content
internal fun JsonObject.underTrue(key:String)=this[key]==JsonPrimitive(true)
private fun JsonObject.reportAge():Double? = (this["report_age_sec"] as? JsonPrimitive)
    ?.takeUnless {it.isString}?.doubleOrNull?.takeIf {it.isFinite() && it>=0}

data class UnderwaterIdentity(val connectionId:String,val generation:Long,val epoch:Long,val source:String)

data class UnderwaterIntent(val direction:String,val speed:Int,val generation:Long) {
    fun encode(sequence:Long):JsonObject {
        require(direction in UNDERWATER_DIRECTIONS && speed in 0..100 && generation>=1 && sequence>=0)
        val moving=direction!="STOP" && speed>0
        return underwaterPacket("op" to "manual_input", "seq" to sequence,
            "control_generation" to generation, "direction" to if(moving) direction else "STOP",
            "speed_percent" to if(moving) speed else 0)
    }
}

/** Entirely separate from surface leases, tasks, maps and the 800 ms surface gate. */
data class UnderwaterState(
    val connection:String="未连接",val connected:Boolean=false,val demo:Boolean=false,
    val sessionId:String="",val clientId:String="",val sequence:Long=-1,
    val report:JsonObject=EMPTY,val receivedAt:Long=-1,val now:Long=0,
    val direction:String="STOP",val speed:Int=25,
    val foreground:Boolean=true,val visible:Boolean=false
) {
    val identity:UnderwaterIdentity? get() {
        val serial=report.underText("connection_id")?.takeIf {it.isNotBlank()} ?: return null
        val generation=report.underLong("control_generation")?.takeIf {it>=1} ?: return null
        val epoch=report.underLong("epoch")?.takeIf {it in 1..0xffffffffL} ?: return null
        val source=report.underText("source")?.takeIf {it in setOf("NANO","RC")} ?: return null
        return UnderwaterIdentity(serial,generation,epoch,source)
    }
    // Receipt time is sampled in the network callback, so GUI queue age is included.
    val feedbackDeadline:Double? get()=report.reportAge()?.let {age ->
        if(receivedAt<0) null else receivedAt.toDouble()+250.0-age*1000.0
    }
    val feedbackFresh:Boolean get()=connected && receivedAt>=0 && now>=receivedAt &&
        report.underTrue("connected") && report.underTrue("control_fresh") &&
        feedbackDeadline?.let {now.toDouble()<it}==true
    val blockReason:String? get() {
        if(!connected) return "等待 H7 水下服务握手"
        if(!foreground) return "应用未在前台"
        if(!visible) return "请打开清洗作业或手动驾驶页"
        if(!feedbackFresh) return "H7 回报未收到或已过期（250 ms）"
        if(report.str("source")=="RC") return "遥控器控制中；需遥控回中停车"
        if(identity==null || report.underLong("firmware_protocol_version")!=6L)
            return "H7 控制身份或 V6 协议未确认"
        if(!report.underTrue("available") || !report.underTrue("motion_enabled") ||
            report.str("source")!="NANO" || !report.underTrue("armed") || report.str("result")!="OK" ||
            report.str("state") !in setOf("READY","ACTIVE"))
            return "水下未就绪：${report.str("reason").ifEmpty {"等待停止握手"}}"
        val owner=report["owner"]
        if(owner!=JsonNull && !(owner is JsonPrimitive && owner.isString && owner.content==clientId))
            return "其他操作端正在控制水下机器人，或控制权尚未确认"
        return null
    }
    val canDrive:Boolean get()=blockReason==null
    val feedback:String get() {
        val board=if(feedbackFresh) "${report.str("source","—")} · ${report.str("direction","—")} · ${report.str("speed_percent","—")}%"
            else "等待新鲜状态"
        return "App 请求：$direction · $speed%\nH7 回报：$board"
    }
}

object UnderwaterReducer {
    fun validEnvelope(p:JsonObject)=p.underText("protocol")==UNDERWATER_PROTOCOL && p.underText("device")==UNDERWATER_DEVICE
    fun hello(s:UnderwaterState,p:JsonObject,now:Long):UnderwaterState? {
        if(!validEnvelope(p) || p.str("type")!="hello") return null
        val client=p.underText("client_id")?.takeIf {it.isNotBlank()} ?: return null
        val session=p.underText("session_id")?.takeIf {it.isNotBlank()} ?: return null
        if(p.arr("capabilities").none {it==JsonPrimitive("under_manual_v1")}) return null
        val output=p.obj("drive_output")
        if(listOf("min" to 0L,"max" to 100L,"step" to 1L,"firmware_protocol_version" to 6L)
                .any {(key,value)->output.underLong(key)!=value}) return null
        return s.copy(connected=true,connection=if(s.demo) "模拟已连接" else "水下已连接",sessionId=session,
            clientId=client,sequence=-1,report=EMPTY,receivedAt=-1,now=now,direction="STOP")
    }
    fun telemetry(s:UnderwaterState,p:JsonObject,receivedAt:Long,now:Long):UnderwaterState {
        if(!s.connected || !validEnvelope(p) || p.str("type")!="telemetry" || p.underText("session_id")!=s.sessionId) return s
        val sequence=p.underLong("sequence")?.takeIf {it>=0 && it>s.sequence} ?: return s
        val report=p["state"] as? JsonObject ?: return s
        if(receivedAt<0 || receivedAt<s.receivedAt || receivedAt>now) return s
        var next=s.copy(sequence=sequence,report=report,receivedAt=receivedAt,now=now)
        // A later packet cannot bridge a period during which the old hold expired.
        if(s.direction!="STOP" && (!s.copy(now=now).canDrive || s.identity!=next.identity) || !next.canDrive)
            next=next.copy(direction="STOP")
        return next
    }
    fun tick(s:UnderwaterState,now:Long):UnderwaterState=s.copy(now=now).let {if(it.canDrive) it else it.copy(direction="STOP")}
    fun disconnected(s:UnderwaterState,reason:String)=s.copy(connected=false,connection=reason,
        sessionId="",clientId="",sequence=-1,report=EMPTY,receivedAt=-1,direction="STOP")
}
