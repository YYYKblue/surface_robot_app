package cn.surface.operator.core

import kotlinx.serialization.json.*

val OperatorState.taskResumeSupported get()="task_resume" in capabilities
val JsonObject.resumeSequence:Long? get()=(this["resume_sequence"] as? JsonPrimitive)
    ?.takeUnless {it.isString}?.longOrNull?.takeIf {it>=0}
val OperatorState.taskPaused get()=task.flag("fresh") && task.str("phase")=="PAUSED"
val OperatorState.canResumeTask get()=taskResumeSupported && taskSupported && operator && foreground &&
    pending.isEmpty() && !picking && preview==null && !exportingLogs &&
    !telemetry.flag("operation_pending") && !runtime.flag("busy") && runtime.obj("operation").isEmpty() &&
    taskPaused && task.str("pause_kind")=="user" && task.str("run_id").length in 1..80 &&
    task["can_resume"]==JsonPrimitive(true) && matchingTaskMap && localization.flag("fresh") && localization.flag("ready") &&
    telemetry.arr("active_tasks").all {(it as? JsonPrimitive)?.content==taskKind} &&
    telemetry.obj("tasks").all {(kind,value)->kind==taskKind || (value as? JsonObject)?.str("phase")!="PAUSED"}

val OperatorState.taskPhaseLabel:String get()=when {
    !fresh || !task.flag("fresh") -> "任务状态未知"
    taskPaused && task.str("pause_kind")=="fault" -> "故障停机 · 请先取消任务"
    taskPaused && taskResumeSupported && task.str("pause_kind")=="user" -> "人工暂停 · 保留原任务"
    taskPaused -> "已暂停 · 取消后重新开始"
    else -> phaseLabels[task.str("phase")] ?: task.str("phase","等待任务状态")
}

private fun resumeBlockerText(blocker:JsonObject):String {
    val message=blocker.str("message")
    val explanation=when {
        "pose drift exceeds resume limits" in message -> "暂停期间位姿漂移超限，请取消后重新开始"
        "fresh post-pause target wall required" in message -> "等待暂停后的新鲜有效墙面观测"
        "localization/perception changed" in message -> "定位或感知会话已变化，请取消任务"
        "no resumable user pause" in message -> "没有可恢复的人工暂停断点，请取消后重新开始"
        "bridge changed during pause" in message -> "控制源或串口会话已变化，原断点失效"
        else -> "继续条件未满足"
    }
    return explanation+if(message.isNotBlank()) "\n原文：$message" else "\n代码：${blocker.str("code","未知")}"
}

val OperatorState.resumeConditionText:String get()=when {
    !fresh -> "连接中断或遥测过期，禁止继续"
    !taskResumeSupported -> "网关不支持暂停续跑，请取消后重新开始；继续功能需要新版 Nano 网关和控制器。"
    !taskSupported || !task.flag("fresh") -> "任务状态未知或过期，等待控制器更新"
    !operator -> "其他操作端占用控制权"
    pending.isNotEmpty() -> "操作结果待确认；不会自动重发"
    !foreground -> "应用不在前台，禁止继续"
    task.str("pause_kind")=="fault" -> "故障停机不能继续；请取消任务，排除故障后重新开始。\n"+
        task.arr("resume_blockers").mapNotNull {it as? JsonObject}.joinToString("\n\n",transform=::resumeBlockerText)
    task.arr("resume_blockers").isNotEmpty() -> task.arr("resume_blockers").mapNotNull {it as? JsonObject}
        .map(::resumeBlockerText).distinct().joinToString("\n\n").ifBlank {"机器人未允许继续，请查看诊断"}
    !matchingTaskMap || !localization.flag("fresh") || !localization.flag("ready") -> "定位未就绪或地图不匹配，不能继续"
    canResumeTask -> "可以继续原任务；路线、覆盖记录和提速设置保持。"
    else -> "控制器尚未确认可以继续；旧控制器需升级，无法恢复的任务请取消后重新开始。"
}
