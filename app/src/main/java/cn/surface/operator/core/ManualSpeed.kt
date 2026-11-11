package cn.surface.operator.core

import kotlinx.serialization.json.*

const val CONTINUOUS_DRIVE_CAPABILITY = "continuous_drive_percent_v1"
val LEGACY_DRIVE_SPEEDS = listOf(25,50,75)

val OperatorState.continuousDrive get()=CONTINUOUS_DRIVE_CAPABILITY in capabilities
fun OperatorState.acceptsManualSpeed(value:Int)=if(continuousDrive) value in 0..100 else value in LEGACY_DRIVE_SPEEDS
fun OperatorState.normalizedManualSpeed(value:Int=manual.speed)=value.takeIf {acceptsManualSpeed(it)} ?: 25

private fun JsonObject.strictInt(key:String):Int? =
    (this[key] as? JsonPrimitive)?.takeUnless {it.isString}?.intOrNull
private fun JsonObject.strictTrue(key:String)=this[key]==JsonPrimitive(true)
private fun JsonObject.nonnegativeNumber(key:String):Double? =
    (this[key] as? JsonPrimitive)?.takeUnless {it.isString}?.doubleOrNull?.takeIf {it.isFinite() && it>=0}

/** Ages are relative durations; never subtract clocks on different machines. */
fun OperatorState.reportedControlAgeSec(field:String):Double? {
    if(receivedAt<0 || now<receivedAt) return null
    val age=telemetry.obj("state").nonnegativeNumber(field) ?: return null
    val stream=telemetry.obj("stream")
    val gatewayAge=if("control_age_ms" in stream) stream.nonnegativeNumber("control_age_ms") ?: return null else 0.0
    return age+gatewayAge/1000.0+(now-receivedAt)/1000.0
}

val OperatorState.firmwareFeedbackFresh:Boolean get()=fresh && telemetry.strictTrue("control_fresh") &&
    telemetry.obj("state").strictTrue("connected") && reportedControlAgeSec("ctrl_age_sec")?.let {it<.4}==true

/** Capability advertises the range, while every telemetry packet renews availability. */
val OperatorState.driveOutputBlockReason:String? get() {
    if(!continuousDrive) return null
    val output=telemetry.obj("drive_output")
    if(output.strictInt("min")!=0 || output.strictInt("max")!=100 || output.strictInt("step")!=1 ||
        output.strictInt("firmware_protocol_version")!=3)
        return "主驱动输出能力未确认，需要 F103 协议 v3"
    if(!output.strictTrue("available"))
        return output.str("reason").ifEmpty {"F103 协议 v3 未就绪，手动输出不可用"}
    if(!firmwareFeedbackFresh) return "F103 控制反馈未收到或已过期"
    val state=telemetry.obj("state")
    if(state.str("source")!="NANO") return "控制源为 ${state.str("source","未知")}，等待 Nano 控制权"
    if(state.str("state") !in setOf("READY","ACTIVE") || !state.strictTrue("armed") || state.str("result")!="OK")
        return "F103 尚未完成控制握手：${state.str("reason").ifEmpty {state.str("state","未知")}}"
    if(reportedControlAgeSec("ack_age_sec")?.let {it<.4}!=true) return "F103 ACK 确认未收到或已过期"
    return null
}

/** Missing fields also participate in comparison, as in Windows' identity tuple. */
data class ManualControlIdentity(val connectionId:JsonElement?,val source:JsonElement?,val epoch:JsonElement?)
val OperatorState.manualControlIdentity:ManualControlIdentity? get() {
    if(!continuousDrive || telemetry.isEmpty()) return null
    val state=telemetry.obj("state")
    return ManualControlIdentity(state["connection_id"],state["source"],state["epoch"])
}

val OperatorState.driveFeedback:String get() {
    val unit=if(continuousDrive) "%" else " 档"
    val request="App 请求：${manual.direction} · ${manual.speed}$unit · ${manual.mode}"
    if(!firmwareFeedbackFresh) return "$request\nF103 回报：状态未收到或已过期"
    val state=telemetry.obj("state")
    val source=state.str("source","未知")
    val speed=state.str("speed_percent","—")
    val scale=when {
        source=="RC" -> "遥控器 $speed 档（独立标度）"
        state.strictInt("firmware_protocol_version")==3 -> "$speed%"
        else -> "$speed 档"
    }
    return "$request\nF103 回报：$source · ${state.str("direction","—")} · $scale"
}
