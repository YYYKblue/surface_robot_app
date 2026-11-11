package cn.surface.operator.core

import kotlinx.serialization.json.*

private fun JsonObject.strictBoolean(key:String):Boolean? =
    (this[key] as? JsonPrimitive)?.takeUnless {it.isString}?.booleanOrNull

val OperatorState.forwardBoostSupported get()="forward_boost" in capabilities
val OperatorState.forwardBoostEnabled:Boolean? get()=
    if(forwardBoostSupported && fresh && task.flag("fresh")) task.strictBoolean("forward_boost_enabled") else null
val OperatorState.forwardBoostPending get()=pending.values.firstOrNull {
    it.command.str("op")=="task" && it.action=="set_forward_boost" && it.command.str("task_type")==taskKind
}
val OperatorState.canSetForwardBoost get()=operator && foreground && page==Page.TASK && taskSupported &&
    forwardBoostEnabled!=null && !busy && pending.isEmpty() && !picking && preview==null && matchingTaskMap &&
    task.str("phase") in setOf("IDLE","CANCELLED","STOPPED","COMPLETE") &&
    task.strictBoolean("forward_boost_editable")==true && task.strictBoolean("can_set_forward_boost")==true

val OperatorState.forwardBoostStatus:String get() {
    forwardBoostPending?.let {
        return if(now-it.sentAt>=5000) "提速设置结果待确认；不会自动重发，请核对机器人状态。"
            else "提速设置尚未确认，等待机器人回复。"
    }
    if(!forwardBoostSupported) return "提速功能需要新版 Nano 网关和清洗控制器。"
    val enabled=forwardBoostEnabled ?: return "提速状态未确认，不能修改。"
    if(!enabled) return "提速已关闭，沿墙前进依靠滚刷。"
    if(taskPaused && taskResumeSupported) return "已启用，暂停期间无任务输出；人工暂停继续后沿用此设置。"
    if(task.strictBoolean("forward_boost_active")==true) return when {
        demo->"模拟：前进辅助指令有效，不驱动硬件。"
        task.strictBoolean("output_enabled")==true->"前进辅助指令有效，不代表实测推力。"
        else->"预览／输出未确认：前进辅助指令有效，不代表硬件已输出。"
    }
    return when(val reason=task.str("forward_boost_reason")) {
        "inactive_task"->"已启用，等待开始下一次任务。"
        "endpoint_cutoff"->"已启用，已进入近端区；本墙段不再提速，仍可纠偏。"
        "heading_correction"->"已启用，航向纠偏中，前进辅助关闭。"
        "waiting_follow"->"已启用，等待稳定沿墙条件。"
        "active"->"已启用，前进辅助有效状态尚未确认。"
        ""->"已启用，等待提速运行状态。"
        else->"已启用，机器人提速状态：$reason"
    }
}
