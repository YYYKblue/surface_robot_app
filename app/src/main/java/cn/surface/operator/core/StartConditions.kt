package cn.surface.operator.core

import kotlinx.serialization.json.JsonObject

private val startBlockerLabels=mapOf(
    "task_stale" to "任务不可用或状态过期", "task_service" to "任务服务不可用",
    "localization" to "定位尚未就绪", "feedback" to "反馈数据未就绪", "pose" to "位姿数据未就绪",
    "scan" to "雷达数据未就绪", "bridge" to "硬件控制条件未满足", "bridge_service" to "控制权服务不可用",
    "map" to "地图未匹配", "clock" to "控制器时钟异常", "phase" to "任务运行中或需先取消暂停任务",
    "task_type" to "任务类型未确认", "schema" to "控制器需要升级", "busy" to "已有任务运行或状态待确认",
    "pending" to "操作处理中", "runtime" to "请先加载地图并等待地图操作结束",
    "preflight" to "控制器尚未确认可以启动"
)

/** Display explanations only. The gateway's can_start and existing safety gates remain authoritative. */
fun startBlockerText(blocker:JsonObject):String {
    val code=blocker.str("code")
    val message=blocker.str("message")
    val explanation=when(message) {
        "pose receive stream stopped" -> "控制器未持续收到新位姿"
        "fresh fixed-map base_link pose required" -> "控制器缺少可用的固定地图船体位姿"
        "pose stale or future stamped" -> "控制器位姿时间戳过旧或位于未来"
        else -> startBlockerLabels[code] ?: "启动条件未满足"
    }
    // Keep the complete source reason visible on touch devices, which have no hover tooltip.
    return explanation+if(message.isNotBlank()) "\n原文：$message" else if(code.isNotBlank()) "\n代码：$code" else ""
}

val OperatorState.startConditionText:String get()=when {
    !fresh -> "连接中断或遥测过期，禁止启动"
    !taskSupported -> "网关未声明完整任务能力"
    !operator -> "其他操作端占用控制权"
    pending.isNotEmpty() -> "操作结果待确认；不会自动重发"
    !foreground -> "应用不在前台，禁止启动"
    picking -> "请先发布或取消初始位姿编辑"
    !mapMatchesRuntime -> "底图与机器人运行会话不一致，等待当前地图"
    // The fresh gateway adds blockers even when a controller is absent/stale,
    // including why the current map cannot provide this task's route.
    task.arr("start_blockers").isNotEmpty() ->
        (if(task.flag("fresh")) "" else "任务状态未知或过期；以下为网关阻断及控制器上次报告：\n\n")+
        task.arr("start_blockers").mapNotNull {it as? JsonObject}.map(::startBlockerText)
            .distinct().joinToString("\n\n").ifBlank {"机器人返回了无法识别的启动条件，请查看诊断"}
    !task.flag("fresh") -> "所选任务状态未知或过期，等待控制器更新"
    canStart -> "启动条件已满足"
    busy -> "机器人正在处理任务或操作"
    else -> "所选控制器尚未允许启动，请查看任务状态与诊断"
}
