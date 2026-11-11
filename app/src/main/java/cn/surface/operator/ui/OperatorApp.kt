package cn.surface.operator.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.surface.operator.BuildConfig
import cn.surface.operator.OperatorViewModel
import cn.surface.operator.core.*
import kotlinx.serialization.json.*
import kotlin.math.roundToInt

val Teal=Color(0xFF087F8C)
val Ink=Color(0xFF193744)
val Muted=Color(0xFF637887)
val Danger=Color(0xFFBA3A35)
private val Background=Color(0xFFF1F5F7)

@Composable fun OperatorApp(vm:OperatorViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme=lightColorScheme(primary=Teal,secondary=Ink,background=Background,surface=Color.White,error=Danger)) {
        Surface(Modifier.fillMaxSize(),color=Background) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Header(s,vm)
                BoxWithConstraints(Modifier.weight(1f)) {
                    val wide=maxWidth>=900.dp
                    val controlWidth=if(maxWidth>=1200.dp) 350.dp else 316.dp
                    if(wide) Row(Modifier.fillMaxSize().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Navigation(s,vm,true)
                        if(s.page in setOf(Page.TASK,Page.MANUAL,Page.MAPPING)) {
                            MapPanel(s,vm::candidate,Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)))
                            Panel(Modifier.width(controlWidth).fillMaxHeight()) { PageContent(s,vm) }
                        } else if(s.page==Page.DIAGNOSTICS) DiagnosticsPanel(s,vm,Modifier.weight(1f).fillMaxHeight())
                        else Panel(Modifier.weight(1f).fillMaxHeight()) { PageContent(s,vm) }
                    } else Column(Modifier.fillMaxSize()) {
                        Navigation(s,vm,false)
                        if(s.page in setOf(Page.TASK,Page.MANUAL,Page.MAPPING)) {
                            MapPanel(s,vm::candidate,Modifier.fillMaxWidth().weight(1f).heightIn(min=180.dp))
                            Panel(Modifier.fillMaxWidth().weight(1.1f)) { PageContent(s,vm) }
                        } else if(s.page==Page.DIAGNOSTICS) DiagnosticsPanel(s,vm,Modifier.fillMaxSize())
                        else Panel(Modifier.fillMaxSize()) { PageContent(s,vm) }
                    }
                }
                Footer(s)
            }
        }
        s.preview?.let { grid ->
            Dialog(onDismissRequest=vm::closePreview,properties=DialogProperties(usePlatformDefaultWidth=false)) {
                Surface(Modifier.fillMaxSize(.9f),shape=RoundedCornerShape(20.dp)) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(s.previewName,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                            TextButton(onClick=vm::closePreview){Text("关闭预览")}
                        }
                        MapPanel(s,{},Modifier.weight(1f),grid)
                    }
                }
            }
        }
    }
}
@Composable private fun Header(s:OperatorState,vm:OperatorViewModel) {
    Row(Modifier.fillMaxWidth().background(Ink).padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text("SURFACE / 水面机器人",color=Color.White,fontWeight=FontWeight.Bold,fontSize=19.sp)
            Text(if(s.demo) "模拟模式 · 不连接硬件" else s.connection,color=if(s.fresh) Color(0xFF89E4CB) else Color(0xFFF2CE8A),fontSize=12.sp)
        }
        if(s.connected) Text(if(!s.fresh) "数据过期" else if(!s.operator) "其他端控制" else if(s.driving) "手动驾驶" else "观察 / 待操作",color=Color.White,fontSize=13.sp)
        if(s.busy || s.canTaskAction("stop")) Button(onClick={vm.task("stop")},enabled=s.canTaskAction("stop"),colors=ButtonDefaults.buttonColors(containerColor=Danger),shape=RoundedCornerShape(12.dp)) {Text("停止任务")}
        Button(onClick=vm::explicitStop,enabled=s.operator,colors=ButtonDefaults.buttonColors(containerColor=Danger),shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(14.dp,12.dp)) {Text("停止手动输出")}
    }
}
@Composable private fun Navigation(s:OperatorState,vm:OperatorViewModel,vertical:Boolean) {
    @Composable fun item(p:Page) {
        val selected=p==s.page
        Surface(color=if(selected) Teal else Color.Transparent,shape=RoundedCornerShape(12.dp),modifier=Modifier.width(76.dp).clickable {vm.page(p)}) {
            Column(Modifier.padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Text(p.symbol,fontSize=22.sp,color=if(selected) Color.White else Muted)
                Text(p.title,fontSize=12.sp,color=if(selected) Color.White else Ink)
            }
        }
    }
    if(vertical) Column(Modifier.width(76.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(5.dp)){Page.entries.forEach {item(it)}}
    else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp),horizontalArrangement=Arrangement.SpaceEvenly){Page.entries.forEach {item(it)}}
}
@Composable private fun Panel(modifier:Modifier,content:@Composable ColumnScope.()->Unit) {
    Surface(modifier,shape=RoundedCornerShape(18.dp)) { Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content) }
}
@Composable private fun PageContent(s:OperatorState,vm:OperatorViewModel) {
    when(s.page) {
        Page.TASK->TaskPage(s,vm)
        Page.MANUAL->ManualPage(s,vm)
        Page.MAPPING->MappingPage(s,vm)
        Page.MAPS->MapsPage(s,vm)
        Page.DIAGNOSTICS->Unit // Dedicated bounded LazyColumn outside the scrolling Panel.
        Page.SETTINGS->Settings(s,vm)
    }
}
@Composable private fun Title(text:String,subtitle:String) {
    Text(text,style=MaterialTheme.typography.headlineSmall,color=Ink,fontWeight=FontWeight.Bold)
    Text(subtitle,style=MaterialTheme.typography.bodySmall,color=Muted)
}
@Composable private fun Info(text:String,warning:Boolean=false) {
    Surface(color=if(warning) Color(0xFFFFF2DD) else Color(0xFFEDF5F6),shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth()) {
        Text(text,Modifier.padding(12.dp),style=MaterialTheme.typography.bodyMedium,color=if(warning) Color(0xFF805414) else Ink)
    }
}
@Composable private fun Action(label:String,enabled:Boolean=true,danger:Boolean=false,onClick:()->Unit) {
    Button(onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(12.dp),
        colors=ButtonDefaults.buttonColors(containerColor=if(danger) Danger else Teal)) {Text(label)}
}
@Composable internal fun TaskPage(s:OperatorState,vm:OperatorViewModel) {
    Title("清洗作业","先定位，再检查启动条件")
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("rectangle" to "矩形清洗","l" to "开放多边清洗").forEach { (kind,label)->
            FilterChip(s.taskKind==kind,{vm.taskKind(kind)},label={Text(label)},enabled=s.canSelectTask)
        }
    }
    val loc=s.localization;val task=s.task
    ForwardBoostPanel(s,vm::setForwardBoost)
    if(s.taskKind=="l") {
        Info("仅执行起始位置之后的墙段，到墙链末端停止；清洗全链请从首边入口附近开始。")
        s.routeChainLabel?.let { label ->
            Text((if(s.fresh && task.flag("fresh")) "" else "上次记录 · ")+label,color=Muted)
            Text("独立墙链之间不自动转场；切换墙链需在机器人端配置并重启。",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
    }
    val locText=when {
        !s.fresh || !loc.flag("fresh")->"定位状态未知或过期"
        !loc.flag("initial_pose_received")->"尚未设置初始位姿"
        loc.flag("ready")->"定位已就绪"
        else->"等待定位稳定 ${"%.1f".format(loc.num("stable_elapsed_sec"))} / ${"%.1f".format(loc.num("stable_sec",1.0))} 秒"
    }
    Info(locText+loc.str("reason").takeIf {it.isNotBlank()}?.let {"\n$it"}.orEmpty())
    OutlinedButton(onClick={vm.pick(!s.picking)},enabled=s.canPose,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(if(s.picking) "取消位姿编辑" else "设置初始位置与船头方向")}
    if(s.picking) {
        s.candidate?.let { Text("x ${"%.2f".format(it.x)} m  ·  y ${"%.2f".format(it.y)} m\n船头 ${"%.1f".format(Math.toDegrees(it.yaw))}°",color=Muted) }
        Action("发布初始位姿",s.canPose && s.candidate!=null,onClick=vm::publishPose)
    }
    HorizontalDivider()
    Text(if(s.fresh && task.flag("fresh")) phaseLabels[task.str("phase")] ?: task.str("phase","等待任务状态") else "任务状态未知",style=MaterialTheme.typography.titleLarge,color=Teal)
    Text(if(s.demo) "模拟，不驱动硬件" else if(s.fresh && task.flag("fresh")) {if(task.flag("output_enabled")) "实际输出模式" else "预览模式，不驱动硬件"} else "输出模式未确认",color=Muted)
    Info(s.startConditionText,!s.canStart)
    if(s.fresh && loc.flag("fresh") && loc.flag("ready") && task.flag("fresh") && !task.flag("can_start"))
        Text("定位就绪与任务启动检查分别判断；请按所选任务的原始原因排查。",style=MaterialTheme.typography.bodySmall,color=Muted)
    Action("开始新任务",s.canStart){vm.task("start")}
    Action("停止任务",s.canTaskAction("stop"),true){vm.task("stop")}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton({vm.task("pause")},enabled=s.canTaskAction("pause"),modifier=Modifier.weight(1f)){Text("暂停")}
        OutlinedButton({vm.task("cancel")},enabled=s.canTaskAction("cancel"),modifier=Modifier.weight(1f)){Text("取消任务")}
    }
    Text("暂停后需取消再开始，不支持断点继续。停止请求成功不代表船体惯性漂移已结束。",style=MaterialTheme.typography.bodySmall,color=Muted)
    if(task.str("run_id").isNotBlank()) Text("任务 ${task.str("run_id")}\n起始墙 ${task.str("start_edge_id","—")} · 当前墙 ${task.str("current_edge_id","—")}",color=Muted)
    if(task.str("reason").isNotBlank()) Text(task.str("reason"),color=Muted)
    if(task.flag("returning_to_start")) Info("正在补齐起始边前段")
    if(s.openRoute) Text("本次仅执行剩余开放墙链，到终点停止；不返回、不补洗。",style=MaterialTheme.typography.bodySmall)
    if(task.str("phase")=="COMPLETE") {
        val result=if(s.openRoute) "本次剩余开放墙链执行完成" else "本次路线执行完成"
        Info((if(s.fresh && task.flag("fresh")) result else "历史结果：$result")+"\n"+if(task.flag("coverage_complete")) "本次要求区间覆盖记录完整" else "覆盖记录仍有缺口",!task.flag("coverage_complete"))
        if(task.long("completed_at_ns")>0) Text("完成时间："+java.time.Instant.ofEpochSecond(task.long("completed_at_ns")/1_000_000_000).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString(),style=MaterialTheme.typography.bodySmall)
        Text("剩余接触区间：${task["remaining_contact_intervals"] ?: "未提供"}",style=MaterialTheme.typography.bodySmall)
    }
    Text("靠近阶段不做路径搜索或环境碰撞检查。",style=MaterialTheme.typography.bodySmall,color=Muted)
}

@Composable private fun HoldButton(label:String,direction:String,s:OperatorState,vm:OperatorViewModel,modifier:Modifier=Modifier) {
    val enabled=s.manualAllowed
    val active=enabled && s.manual.direction==direction
    val currentDirection by rememberUpdatedState<(String)->Unit>(vm::direction)
    val currentCancel by rememberUpdatedState<()->Unit>({vm.stopManual("touch_cancelled")})
    Surface(modifier.sizeIn(minWidth=72.dp,minHeight=72.dp).semantics {contentDescription="按住$label，松开停止主驱动"}
        .pointerInput(enabled,s.manualControlIdentity) {
            if(enabled) awaitEachGesture {
                // Waiting for a touch is not a gesture. Recomposition or a new
                // control identity must not make idle buttons stop auxiliaries.
                val down=awaitFirstDown(requireUnconsumed=false)
                var released=false
                try {
                    down.consume();currentDirection(direction)
                    while(true) {
                        val event=awaitPointerEvent()
                        val pointer=event.changes.find {it.id==down.id}
                        if(pointer==null || event.changes.count {it.pressed}>1 || pointer.isConsumed) break
                        if(!pointer.pressed) {released=true;pointer.consume();break}
                        if(pointer.position.x !in 0f..size.width.toFloat() || pointer.position.y !in 0f..size.height.toFloat()) break
                        pointer.consume()
                    }
                } finally {if(released) currentDirection("STOP") else currentCancel()}
            }
        },shape=RoundedCornerShape(16.dp),color=if(active) Teal else if(enabled) Color(0xFFE8F2F4) else Color(0xFFEDEFF1)) {
        Box(contentAlignment=Alignment.Center){Text(label,fontSize=23.sp,color=if(active) Color.White else if(enabled) Ink else Color.Gray)}
    }
}
@Composable private fun ManualPage(s:OperatorState,vm:OperatorViewModel) {
    Title("手动驾驶","按住运行 · 松开方向键仅停止主驱动")
    if(!s.manualAllowed) Info(when {
        !s.fresh -> "等待新鲜遥测"
        !s.operator -> "其他操作端占用控制权"
        s.driveOutputBlockReason!=null -> s.driveOutputBlockReason!!
        else -> "任务运行、操作待确认或界面中断时禁止驾驶"
    },true)
    else Text("可直接按方向键或开启辅助输出",style=MaterialTheme.typography.bodySmall,color=Teal)
    Text(if(s.continuousDrive) "主驱动 PWM · 0–100%" else "主驱动档位 · 旧网关",fontWeight=FontWeight.SemiBold)
    if(s.continuousDrive) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            OutlinedButton({vm.speed(s.manual.speed-1)},enabled=s.manualAllowed && s.manual.speed>0) {Text("−1")}
            Text("${s.manual.speed}%",fontSize=22.sp,fontWeight=FontWeight.Bold,color=Teal)
            OutlinedButton({vm.speed(s.manual.speed+1)},enabled=s.manualAllowed && s.manual.speed<100) {Text("+1")}
        }
        Slider(s.manual.speed.toFloat(),{vm.speed(it.roundToInt())},valueRange=0f..100f,steps=99,
            enabled=s.manualAllowed,modifier=Modifier.semantics {contentDescription="主驱动输出百分比"})
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf(25,50,75).forEach {v->FilterChip(s.manual.speed==v,{vm.speed(v)},label={Text(if(s.continuousDrive) "$v%" else "$v 档")},enabled=s.manualAllowed)}}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("FRONT" to "前轮","BOTH" to "全部","BACK" to "后轮").forEach {(v,label)->FilterChip(s.manual.mode==v,{vm.driveMode(v)},label={Text(label)},enabled=s.manualAllowed)}}
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        HoldButton("↑","FORWARD",s,vm)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            HoldButton("←","LEFT",s,vm)
            FilledTonalButton({vm.direction("STOP")},enabled=s.manualAllowed,modifier=Modifier.size(72.dp),shape=RoundedCornerShape(16.dp)){Text("停")}
            HoldButton("→","RIGHT",s,vm)
        }
        HoldButton("↓","REVERSE",s,vm)
    }
    Text(s.driveFeedback,style=MaterialTheme.typography.bodySmall,color=Muted)
    Text(if(s.continuousDrive) "百分比表示 PWM 输出，并非实测航速。0% 仅停主驱动；提高后需重新按方向。" else "旧网关仅支持 25 / 50 / 75 档，并非实测航速。",style=MaterialTheme.typography.bodySmall,color=Muted)
    Auxiliary("滚刷",s.manual.brush,s.manual.brushPercent,s.manualAllowed,vm::brush,vm::brushPercent)
    Auxiliary("第五电推",s.manual.thrust,s.manual.thrustPercent,s.manualAllowed && s.telemetry.obj("state").flag("allow_fifth_thruster"),vm::thrust,vm::thrustPercent)
    Text("滚刷和第五电推独立运行；顶部“停止手动输出”关闭全部手动输出。",style=MaterialTheme.typography.bodySmall,color=Muted)
}
@Composable private fun Auxiliary(label:String,on:Boolean,value:Int,enabled:Boolean,toggle:(Boolean)->Unit,change:(Int)->Unit) {
    HorizontalDivider()
    Row(verticalAlignment=Alignment.CenterVertically) {Text(label,Modifier.weight(1f),fontWeight=FontWeight.SemiBold);Text("$value%");Switch(on,toggle,enabled=enabled)}
    Slider(value.toFloat(),{change(it.toInt())},valueRange=-100f..100f,steps=39,enabled=enabled)
}
@Composable private fun MappingPage(s:OperatorState,vm:OperatorViewModel) {
    var name by remember {mutableStateOf("")}
    var confirm by remember {mutableStateOf(false)}
    Title("建图","使用手动驾驶探索，地图保存在机器人端")
    Text("新版网关的建图底图默认最多每 2 秒更新一次，船体位姿独立刷新。",style=MaterialTheme.typography.bodySmall,color=Muted)
    Info(if(s.mappingSupported) "模式：${s.runtime.str("phase","待机")}\n${s.runtime.str("message")}" else "需要受管网关 operator_system.launch.py",!s.mappingSupported)
    Action("开始建图",s.canMapping("start")){vm.mapping("start")}
    OutlinedButton({vm.page(Page.MANUAL)},modifier=Modifier.fillMaxWidth()){Text("前往手动驾驶探索")}
    OutlinedTextField(name,{if(it.length<=80) name=it},label={Text("地图名称")},placeholder={Text("留空自动命名")},modifier=Modifier.fillMaxWidth().onFocusChanged {if(it.isFocused) vm.stopManual("text_input")},singleLine=true)
    Action("保存快照并继续",s.canMapping("save")){vm.mapping("save",name)}
    Action("保存并结束建图",s.canMapping("save_end")){vm.mapping("save_end",name)}
    OutlinedButton({vm.stopManual("confirm_discard");confirm=true},enabled=s.canMapping("discard"),modifier=Modifier.fillMaxWidth()){Text("放弃未保存的建图",color=Danger)}
    val saved=s.runtime.obj("last_saved")
    if(saved.isNotEmpty()) Info("最近保存：${saved.str("name")}\n${saved.arr("paths").joinToString("\n") {(it as? JsonPrimitive)?.contentOrNull.orEmpty()}}")
    Text("保存位置：${s.runtime.str("maps_directory","Nano 工作空间 maps/")}\n保存失败会保留建图；可到地图库重试导出。",style=MaterialTheme.typography.bodySmall,color=Muted)
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("放弃本次建图？")},text={Text("未保存的内容将丢失，此前保存的地图会保留。")},confirmButton={TextButton({confirm=false;vm.mapping("discard",confirmed=true)}){Text("放弃",color=Danger)}},dismissButton={TextButton({confirm=false}){Text("返回")}})
}
@Composable private fun MapsPage(s:OperatorState,vm:OperatorViewModel) {
    Title("地图库","预览与加载分开，加载后需重新设置初始位姿")
    Text("当前地图：${s.runtime.str("current_map","未加载").ifBlank {"未加载"}}",color=Teal)
    Action("刷新列表",s.canMapping("list")){vm.mapping("list")}
    if(s.catalog.isEmpty()) Info("尚无地图列表，请连接受管网关后刷新。")
    s.catalog.forEach {entry->
        val name=entry.str("name")
        Surface(Modifier.fillMaxWidth().clickable(enabled=s.pending.isEmpty()){vm.selectMap(name)},shape=RoundedCornerShape(12.dp),color=if(s.selectedMap==name) Color(0xFFDDF1F0) else Background) {
            Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                RadioButton(s.selectedMap==name,{vm.selectMap(name)},enabled=s.pending.isEmpty())
                Column {Text(name,fontWeight=FontWeight.SemiBold);Text(if(entry.flag("valid")) "文件完整 · ${entry.str("saved_at")}" else if(entry.flag("recoverable")) "待导出 · ${entry.str("reason")}" else "不可用 · ${entry.str("reason")}",style=MaterialTheme.typography.bodySmall,color=Muted)}
            }
        }
    }
    Action("预览所选地图",s.canMapping("preview")){vm.mapping("preview")}
    Action("加载所选地图",s.canMapping("load")){vm.mapping("load")}
    OutlinedButton({vm.mapping("retry_export")},enabled=s.canMapping("retry_export"),modifier=Modifier.fillMaxWidth()){Text("重试快照导出")}
    Info("建图和固定地图定位互斥。地图保存成功不代表适用于全部清洗任务。")
}
@Composable private fun Settings(s:OperatorState,vm:OperatorViewModel) {
    var url by remember {mutableStateOf(vm.savedUrl)}
    Title("连接设置","平板与 Nano 接入同一局域网")
    OutlinedTextField(url,{url=it},label={Text("网关 WebSocket 地址")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    Action("连接机器人"){vm.connect(url)}
    OutlinedButton({vm.connect(url,true)},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("进入模拟模式 · 不连接硬件")}
    OutlinedButton(vm::disconnect,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("断开连接 / 释放操作端")}
    Info("连接后先观察状态。手动页可直接按方向键，无需申请；离页、失焦或中断后需重新操作。\n断线不会主动取消已经启动的自动任务；退出不会结束机器人端建图。")
    Text("当前版本 ${BuildConfig.VERSION_NAME} · f1-operator-v1\n主要适配 11.5 英寸横屏，同时支持竖屏与分屏。\n当前版本面向可信局域网现有 ws:// 网关。",color=Muted)
}
@Composable private fun Footer(s:OperatorState) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFE6EEF1)).padding(horizontal=16.dp,vertical=6.dp)) {
        val distance=s.wallDistance
        Text("船体中心距左墙：${distance?.let {"%.3f m".format(it)} ?: "无有效观测"}  ·  "+
            if(s.task.obj("actual_track").flag("truncated")) "历史轨迹已截断" else "历史轨迹 ${s.task.obj("actual_track").long("point_count")} 点",
            style=MaterialTheme.typography.labelMedium,color=Ink)
        Text(s.message,maxLines=2,style=MaterialTheme.typography.bodySmall,color=Muted)
    }
}
