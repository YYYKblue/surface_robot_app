package cn.surface.operator.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.surface.operator.OperatorViewModel
import cn.surface.operator.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable fun DiagnosticsPanel(s:OperatorState,vm:OperatorViewModel,modifier:Modifier=Modifier) {
    var expanded by remember {mutableStateOf(false)}
    var clearConfirm by remember {mutableStateOf(false)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain"),vm::finishLogExport)
    Surface(modifier,shape=RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("诊断日志",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,color=Ink)
            Text("保留最近 2000 条 · Nano 时间可能与平板存在偏差 · 日志不代表当前控制状态",style=MaterialTheme.typography.bodySmall,color=Muted)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={
                    if(vm.beginLogExport()) {
                        try {save.launch("SurfaceOperator-${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.txt")}
                        catch(_:android.content.ActivityNotFoundException) {vm.finishLogExport(null);vm.notice("系统未提供文件保存界面")}
                    }
                },enabled=!s.exportingLogs) {Text(if(s.exportingLogs) "正在导出…" else "导出全部日志")}
                OutlinedButton(onClick={expanded=true}) {Text("展开日志")}
                TextButton(onClick={clearConfirm=true}) {Text("清空本地日志",color=Danger)}
            }
            LogBrowser(s,vm,Modifier.weight(1f),showSummary=true)
        }
    }
    if(expanded) Dialog(onDismissRequest={expanded=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(.96f),shape=RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("诊断日志 · ${s.logs.size} 条",style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick={expanded=false}) {Text("关闭展开")}
                }
                LogBrowser(s,vm,Modifier.weight(1f),showSummary=false)
            }
        }
    }
    if(clearConfirm) AlertDialog(onDismissRequest={clearConfirm=false},title={Text("清空本地日志？")},
        text={Text("清除当前 App 保留的记录，不删除机器人日志。已接收历史不会再次补入；需要留存请先导出。")},
        confirmButton={TextButton(onClick={clearConfirm=false;vm.clearLogs()}) {Text("清空",color=Danger)}},
        dismissButton={TextButton(onClick={clearConfirm=false}) {Text("返回")}})
}

@Composable private fun LogBrowser(s:OperatorState,vm:OperatorViewModel,modifier:Modifier,showSummary:Boolean) {
    var source by remember {mutableStateOf<LogSource?>(null)}
    var query by remember {mutableStateOf("")}
    var summaryExpanded by remember {mutableStateOf(false)}
    var pendingConfirm by remember {mutableStateOf(false)}
    val entries=remember(s.logs,source,query) {s.logs.filter {it.matches(source,query.trim())}}
    val scroll=rememberLazyListState()
    val scope=rememberCoroutineScope()
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        @Composable fun filters() {
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(source==null,{source=null},label={Text("全部来源")})
                LogSource.entries.forEach {entry -> FilterChip(source==entry,{source=entry},label={Text(entry.label)})}
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if(maxWidth>=700.dp) Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                filters()
                OutlinedTextField(query,{query=it.take(200)},label={Text("搜索原因、参数或事件编号")},singleLine=true,modifier=Modifier.weight(1f))
            } else Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                filters()
                OutlinedTextField(query,{query=it.take(200)},label={Text("搜索原因、参数或事件编号")},singleLine=true,modifier=Modifier.fillMaxWidth())
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("显示 ${entries.size} / ${s.logs.size} 条 · 最新在前",style=MaterialTheme.typography.bodySmall,color=Muted)
            TextButton(onClick={scope.launch {scroll.scrollToItem(0)}}) {Text("回到最新")}
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f),state=scroll,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(showSummary) item(key="summary") {
                Surface(color=Color(0xFFEDF5F6),shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        val valid=s.fresh && s.telemetry.flag("control_fresh")
                        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                            Text(if(valid) "当前桥状态" else "状态未知或过期 · 下列仅为最近收到的值",modifier=Modifier.weight(1f),color=if(valid) Teal else Danger,fontWeight=FontWeight.Bold)
                            TextButton(onClick={summaryExpanded=!summaryExpanded}) {Text(if(summaryExpanded) "收起状态详情" else "查看状态详情")}
                        }
                        Text(bridgeSummary(s,summaryExpanded),style=MaterialTheme.typography.bodyMedium)
                        if(summaryExpanded || "diagnostic_history" !in s.capabilities)
                            Text(if("diagnostic_history" in s.capabilities) "网关支持最近 50 条历史补读" else "未声明诊断历史能力，仅显示本地记录与收到的桥状态",style=MaterialTheme.typography.bodySmall,color=Muted)
                    }
                }
            }
            if(showSummary && s.pending.isNotEmpty()) item(key="pending") {
                Text("待确认操作："+s.pending.values.joinToString("；") {"${it.command.str("op")}/${it.action} ${(s.now-it.sentAt)/1000}s"},color=Danger)
                OutlinedButton(onClick={pendingConfirm=true},enabled=s.fresh && !s.busy && s.pending.values.all {s.now-it.sentAt>5000}) {Text("已核对状态，清除本地待确认")}
            }
            if(entries.isEmpty()) item(key="empty") {Text("暂无符合条件的日志",Modifier.padding(16.dp),color=Muted)}
            items(entries,key={"event-${it.key}"}) {entry -> LogCard(entry)}
        }
    }
    if(pendingConfirm) AlertDialog(onDismissRequest={pendingConfirm=false},title={Text("清除本地待确认提示？")},
        text={Text("这不会停止机器人，也不会重发请求。仅在已核对任务与建图状态后使用。")},
        confirmButton={TextButton(onClick={pendingConfirm=false;vm.clearUncertain()}) {Text("已核对，清除")}},
        dismissButton={TextButton(onClick={pendingConfirm=false}) {Text("返回")}})
}

@Composable private fun LogCard(entry:DiagnosticEntry) {
    var expanded by remember(entry.key) {mutableStateOf(false)}
    Surface(Modifier.fillMaxWidth().clickable {expanded=!expanded},shape=RoundedCornerShape(10.dp),color=Color(0xFFF3F6F8)) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text("${entry.stamp} · ${entry.source.label} · ${if(entry.nanoTime) "Nano 时间" else "平板记录时间"}"+
                (entry.eventId?.let {" · #$it"} ?: ""),style=MaterialTheme.typography.labelSmall,color=Muted)
            Text(entry.message,color=Ink,fontWeight=FontWeight.SemiBold)
            if(entry.details.isNotEmpty()) Text(entry.detailText,maxLines=if(expanded) Int.MAX_VALUE else 2,style=MaterialTheme.typography.bodySmall,color=Muted)
            if(expanded) SelectionContainer {
                Text("连接：${entry.target}\n"+(if(entry.session.isNotEmpty()) "会话：${entry.session}\n" else "")+"原始详情：${entry.details}",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun bridgeSummary(s:OperatorState,expanded:Boolean):String {
    val state=s.telemetry.obj("state")
    fun field(key:String):String=(state[key] as? JsonPrimitive)?.contentOrNull?.ifBlank {"无"} ?: "未知"
    return buildString {
        append("设备：${field("state")} · 控制源：${field("source")} · 输出许可：${field("motion_authorized")}\n")
        append("CTRL / ACK 年龄：${field("ctrl_age_sec")} / ${field("ack_age_sec")} s\n")
        val reason=field("last_stop_reason")
        append("最近撤销：${diagnosticReasons[reason]?.let {"$it ($reason)"} ?: reason}")
        if(expanded) {
            append("\n指令剩余：${field("command_remaining_sec")} s · 最大发送间隔：${field("max_tx_gap_sec")} s")
            append("\n串口重连：${field("reconnects")} · 拒绝指令：${field("rejected_commands")}")
            append("\n控制者：${field("owner")} · 原因：${field("reason")}")
            append("\n连接：${s.connection} · 活动操作端：${s.telemetry.str("active_client","未占用")}")
            append("\n网关日志会话：${s.telemetry.obj("diagnostics").str("session","未提供")}")
        }
    }
}
