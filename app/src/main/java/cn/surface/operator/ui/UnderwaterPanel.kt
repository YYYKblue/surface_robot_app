package cn.surface.operator.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.surface.operator.core.UnderwaterState
import kotlin.math.roundToInt

/** Independent controls: surface task, map and lease state never enter this panel. */
@Composable internal fun UnderwaterPanel(
    s:UnderwaterState,
    direction:(String)->Unit,
    speed:(Int)->Unit,
    stop:(String)->Unit,
    settings:()->Unit
) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("水下机器人",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=Ink)
        Text(if(s.demo) "模拟模式 · 不驱动硬件" else s.connection,style=MaterialTheme.typography.bodySmall,color=Muted)
        Surface(color=if(s.canDrive) Color(0xFFEDF5F6) else Color(0xFFFFF2DD),shape=RoundedCornerShape(10.dp)) {
            Text(s.blockReason ?: "可操作 · 与水面控制独立",Modifier.fillMaxWidth().padding(10.dp),style=MaterialTheme.typography.bodySmall,color=Ink)
        }
        if(!s.connected) OutlinedButton(settings,modifier=Modifier.fillMaxWidth()) {Text("设置水下连接")}
        UnderwaterDirectionPad(s,direction,stop)
        Button({stop("under_button")},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),
            colors=ButtonDefaults.buttonColors(containerColor=Danger)) {Text("水下手动停止")}
        Text("行走输出幅度 · 0–100%",fontWeight=FontWeight.SemiBold,color=Ink)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            OutlinedButton({speed(s.speed-1)},enabled=s.speed>0,contentPadding=PaddingValues(horizontal=10.dp)) {Text("−1")}
            Text("${s.speed}%",fontSize=22.sp,fontWeight=FontWeight.Bold,color=Teal)
            OutlinedButton({speed(s.speed+1)},enabled=s.speed<100,contentPadding=PaddingValues(horizontal=10.dp)) {Text("+1")}
        }
        Slider(s.speed.toFloat(),{speed(it.roundToInt())},valueRange=0f..100f,steps=99,
            modifier=Modifier.semantics {contentDescription="水下行走输出百分比"})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            listOf(25,50,75).forEach {value -> FilterChip(s.speed==value,{speed(value)},label={Text("$value%")}) }
        }
        Text(s.feedback,style=MaterialTheme.typography.bodySmall,color=Muted)
        Text("按住运行，松开停止；← / → 为左转 / 右转。0% 会清除方向，调高后需重新按键。",style=MaterialTheme.typography.bodySmall,color=Muted)
        Text("前进时前滚刷随动；运动时顶升目标 40%。百分比不是实测速度。遥控接管时，App 停止不覆盖遥控输出，需遥控回中停车。",style=MaterialTheme.typography.bodySmall,color=Muted)
    }
}

/** One gesture owner for this pad, so conflicting underwater fingers stay stopped
 * until all are lifted. A finger on the surface robot's separate pad is unrelated. */
@Composable internal fun UnderwaterDirectionPad(s:UnderwaterState,direction:(String)->Unit,stop:(String)->Unit) {
    val enabled=s.canDrive && s.speed>0
    val currentDirection by rememberUpdatedState(direction)
    val currentStop by rememberUpdatedState(stop)
    val currentEnabled by rememberUpdatedState(enabled)
    val controlToken=remember(enabled,s.identity) {Any()}
    val currentToken by rememberUpdatedState(controlToken)
    val activeGesture=remember {booleanArrayOf(false)}
    DisposableEffect(controlToken) {
        onDispose {
            if(activeGesture[0]) {
                activeGesture[0]=false
                currentStop("under_control_changed")
            }
        }
    }
    val cells=mapOf(1 to "FORWARD",3 to "LEFT",4 to "STOP",5 to "RIGHT",7 to "REVERSE")
    val labels=mapOf("FORWARD" to "↑","LEFT" to "←","STOP" to "停","RIGHT" to "→","REVERSE" to "↓")
    BoxWithConstraints(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
        val padSize=minOf(maxWidth,224.dp)
        Box(Modifier.size(padSize).testTag("underwater-direction-pad")
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down=awaitFirstDown(requireUnconsumed=false)
                    fun cell(x:Float,y:Float):String? {
                        if(x<0 || y<0 || x>=size.width || y>=size.height) return null
                        return cells[(y/(size.height/3f)).toInt()*3+(x/(size.width/3f)).toInt()]
                    }
                    val first=cell(down.position.x,down.position.y)
                    if(first==null || first!="STOP" && !currentEnabled) return@awaitEachGesture
                    // Keep this gesture owner until release even when identity
                    // changes. A second finger cannot restart the old hold.
                    val gestureToken=currentToken
                    var released=false
                    var blocked=first=="STOP"
                    val tracked=mutableSetOf<PointerId>(down.id)
                    down.consume()
                    if(blocked) currentStop("under_button") else {
                        activeGesture[0]=true
                        currentDirection(first)
                    }
                    try {
                        while(true) {
                            val event=awaitPointerEvent()
                            event.changes.filter {it.pressed && !it.previousPressed &&
                                cell(it.position.x,it.position.y)!=null}.forEach {tracked.add(it.id)}
                            val presses=event.changes.filter {it.id in tracked && it.pressed}
                            val primary=event.changes.find {it.id==down.id}
                            if(!blocked && (currentToken!==gestureToken || !currentEnabled || presses.size>1 || primary==null ||
                                primary.pressed && (primary.isConsumed || cell(primary.position.x,primary.position.y)!=first))) {
                                blocked=true
                                if(activeGesture[0]) {activeGesture[0]=false;currentStop("under_touch_cancelled")}
                            }
                            event.changes.filter {it.id in tracked}.forEach {it.consume()}
                            if(primary!=null && !primary.pressed && !blocked) {
                                currentDirection("STOP")
                                activeGesture[0]=false
                                blocked=true
                            }
                            if(presses.isEmpty()) {released=true;break}
                        }
                    } finally {
                        if(!released && activeGesture[0]) {activeGesture[0]=false;currentStop("under_touch_cancelled")}
                    }
                }
            }) {
            Column(Modifier.fillMaxSize()) {
                repeat(3) {row -> Row(Modifier.weight(1f)) {
                    repeat(3) {column ->
                        val value=cells[row*3+column]
                        if(value==null) Spacer(Modifier.weight(1f).fillMaxHeight())
                        else {
                            val selected=enabled && value!="STOP" && s.direction==value
                            Surface(Modifier.weight(1f).fillMaxHeight().padding(3.dp).semantics {
                                contentDescription=if(value=="STOP") "水下方向盘停止" else "水下按住${labels[value]}，松开停止"
                                if(value=="STOP") onClick {currentStop("under_button");true}
                            },shape=RoundedCornerShape(14.dp),
                                color=if(selected) Teal else if(enabled || value=="STOP") Color(0xFFE8F2F4) else Color(0xFFEDEFF1)) {
                                Box(contentAlignment=Alignment.Center) {Text(labels[value].orEmpty(),fontSize=24.sp,
                                    color=if(selected) Color.White else if(enabled || value=="STOP") Ink else Color.Gray)}
                            }
                        }
                    }
                } }
            }
        }
    }
}
