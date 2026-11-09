package cn.surface.operator.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import cn.surface.operator.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.math.*

@Composable
fun MapPanel(state:OperatorState,onPose:(Pose?)->Unit,modifier:Modifier=Modifier,preview:GridMap?=null) {
    val grid=preview ?: state.map
    var zoom by remember(grid) { mutableFloatStateOf(1f) }
    var pan by remember(grid) { mutableStateOf(Offset.Zero) }
    var pixels by remember { mutableStateOf(IntSize(1,1)) }
    var showScan by remember { mutableStateOf(true) }
    var showTrack by remember { mutableStateOf(true) }
    val centerTrack=if(preview==null && showTrack) state.centerTrackOverlay(grid) else null
    val markerPaint=remember {android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {color=0xFFB45B16.toInt()}}
    val image by produceState<ImageBitmap?>(null,grid) {
        value=null
        value=withContext(Dispatchers.Default) { grid?.let { g ->
            val colors=IntArray(g.cells.size) { i ->
                val cell=g.cells[(g.height-1-i/g.width)*g.width+i%g.width].toInt()
                when { cell<0->0xFFE5EBEF.toInt(); cell>50->0xFF485E6C.toInt(); else->0xFFFAFCFD.toInt() }
            }
            Bitmap.createBitmap(colors,g.width,g.height,Bitmap.Config.ARGB_8888).asImageBitmap()
        } }
    }
    val transform=grid?.let { MapTransform(it,pixels.width.toDouble(),pixels.height.toDouble(),zoom.toDouble(),Point(pan.x.toDouble(),pan.y.toDouble())) }
    val currentTransform by rememberUpdatedState(transform)
    val currentPoseCallback by rememberUpdatedState(onPose)
    val picking=preview==null && state.picking
    Column(modifier.background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if(preview!=null) "保存地图预览" else "现场地图",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
            TextButton(onClick={zoom=1f;pan=Offset.Zero}) { Text("全图") }
            TextButton(onClick={
                val p=state.telemetry.obj("pose")
                if(state.fresh && p.isNotEmpty() && transform!=null) {
                    val at=transform.worldToScreen(Point(p.num("x"),p.num("y")))
                    pan+=Offset(pixels.width/2f-at.x.toFloat(),pixels.height/2f-at.y.toFloat())
                }
            },enabled=preview==null && state.fresh && state.telemetry.obj("pose").isNotEmpty()) { Text("船体回中") }
        }
        Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            if(preview==null) {
                FilterChip(showScan,{showScan=!showScan},label={Text("雷达")})
                Spacer(Modifier.width(8.dp))
                FilterChip(showTrack,{showTrack=!showTrack},label={Text("轨迹")})
            }
            Spacer(Modifier.weight(1f))
            Text("${"%.1f".format(zoom)}×",color=Muted)
            TextButton(onClick={zoom=(zoom/1.4f).coerceAtLeast(.5f)}) { Text("−") }
            TextButton(onClick={zoom=(zoom*1.4f).coerceAtMost(10f)}) { Text("＋") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0xFFF0F4F6))) {
            var start by remember { mutableStateOf<Offset?>(null) }
            var end by remember { mutableStateOf<Offset?>(null) }
            Canvas(Modifier.fillMaxSize().semantics {
                centerTrack?.let {contentDescription="船中心停车点：${it.stops.size} 个，距墙端 ${centimeters(it.stop)} cm"}
            }.onSizeChanged {pixels=it}.pointerInput(picking,grid) {
                if(picking) detectDragGestures(
                    onDragStart={start=it;end=it;currentPoseCallback(null)},
                    onDragCancel={start=null;end=null;currentPoseCallback(null)},
                    onDragEnd={
                        val a=start;val b=end;val tr=currentTransform
                        if(a!=null && b!=null && tr!=null && (b-a).getDistance()>=20.dp.toPx()) {
                            val p=tr.screenToWorld(Point(a.x.toDouble(),a.y.toDouble()),true)
                            val q=tr.screenToWorld(Point(b.x.toDouble(),b.y.toDouble()))
                            if(p!=null && q!=null) currentPoseCallback(Pose(p.x,p.y,atan2(q.y-p.y,q.x-p.x),tr.map.revision))
                        }
                        start=null;end=null
                    },onDrag={change,_ -> end=change.position;change.consume()}
                ) else detectTransformGestures { centroid,delta,factor,_ ->
                    val newZoom=(zoom*factor).coerceIn(.5f,10f)
                    val center=Offset(size.width/2f,size.height/2f)
                    pan=(pan+center-centroid)*(newZoom/zoom)+centroid-center+delta
                    zoom=newZoom
                }
            }) {
                // Draw against this frame's canvas bounds. onSizeChanged state can
                // lag a layout frame when the parameter legend first appears.
                val tr=grid?.let {MapTransform(it,size.width.toDouble(),size.height.toDouble(),zoom.toDouble(),Point(pan.x.toDouble(),pan.y.toDouble()))}
                val bitmap=image
                if(tr!=null && bitmap!=null) {
                    drawImage(bitmap,dstOffset=IntOffset(tr.left.roundToInt(),(tr.bottom-grid.height*grid.resolution*tr.scale).roundToInt()),
                        dstSize=IntSize((grid.width*grid.resolution*tr.scale).roundToInt().coerceAtLeast(1),(grid.height*grid.resolution*tr.scale).roundToInt().coerceAtLeast(1)),filterQuality=FilterQuality.None)
                    fun screen(p:Point):Offset=tr.worldToScreen(p).let {Offset(it.x.toFloat(),it.y.toFloat())}
                    fun line(points:List<Point>,color:Color,dashed:Boolean=false) {
                        if(points.isEmpty()) return
                        val path=Path();val first=screen(points.first());path.moveTo(first.x,first.y)
                        points.drop(1).forEach {val p=screen(it);path.lineTo(p.x,p.y)}
                        drawPath(path,color,style=Stroke(2.5.dp.toPx(),pathEffect=if(dashed) PathEffect.dashPathEffect(floatArrayOf(10f,7f)) else null))
                    }
                    fun arrow(x:Double,y:Double,yaw:Double,color:Color) {
                        if(!x.isFinite()||!y.isFinite()||!yaw.isFinite()) return
                        val p=screen(Point(x,y));val a=yaw-grid.yaw;val length=19.dp.toPx()
                        val path=Path().apply {
                            moveTo(p.x+length*cos(a).toFloat(),p.y-length*sin(a).toFloat())
                            lineTo(p.x-length*.65f*cos(a+.75).toFloat(),p.y+length*.65f*sin(a+.75).toFloat())
                            lineTo(p.x-length*.65f*cos(a-.75).toFloat(),p.y+length*.65f*sin(a-.75).toFloat());close()
                        };drawPath(path,color);drawPath(path,Color.White,style=Stroke(2f))
                    }
                    if(preview==null) {
                        val task=state.task;val expected=task.obj("expected_track");val actual=task.obj("actual_track")
                        val validExpected=state.matchingTaskMap && expected.str("frame_id")==grid.frame && expected.str("map_id")==task.str("map_id")
                        if(showTrack && state.fresh && task.flag("fresh") && validExpected) {
                            expected.arr("cleaning_segments").forEach { e -> (e as? JsonObject)?.arr("points")?.polylines()?.forEach {line(it,Color(0xFF2A6FDF),true)} }
                            expected.arr("navigation_path").polylines().forEach {line(it,Color(0xFF9252B5),true)}
                            val region=expected.obj("start_region")
                            val polygon=region.arr("polygon").mapNotNull {it.point()}
                            if(polygon.size==4) line(polygon+polygon.first(),Color(0xFF9252B5),true)
                            region["reference_wall_point"]?.point()?.let { drawCircle(Color(0xFF9252B5),5.dp.toPx(),screen(it)) }
                            centerTrack?.let { overlay ->
                                markerPaint.textSize=11.sp.toPx()
                                val label="${centimeters(overlay.stop)} cm"
                                val half=4.dp.toPx()
                                overlay.stops.forEach { point ->
                                    val p=screen(point)
                                    if(p.x !in 0f..size.width || p.y !in 0f..size.height) return@forEach
                                    val topLeft=p-Offset(half,half)
                                    val boxSize=androidx.compose.ui.geometry.Size(half*2,half*2)
                                    drawRect(Color(0xFFFFF3DF),topLeft,boxSize)
                                    drawRect(Color(0xFFB45B16),topLeft,boxSize,style=Stroke(2.dp.toPx()))
                                    val tx=(p.x+7.dp.toPx()).coerceIn(0f,(size.width-markerPaint.measureText(label)).coerceAtLeast(0f))
                                    val ty=(p.y-7.dp.toPx()).coerceIn(markerPaint.textSize,size.height.coerceAtLeast(markerPaint.textSize))
                                    drawContext.canvas.nativeCanvas.drawText(label,tx,ty,markerPaint)
                                }
                            }
                        }
                        if(showTrack && state.matchingTaskMap && actual.str("frame_id")==grid.frame && actual.str("map_id")==task.str("map_id") && actual.str("run_id")==task.str("run_id"))
                            actual.arr("segments").forEach { e -> (e as? JsonArray)?.polylines()?.forEach {line(it,Teal)} }
                        if(showScan && state.fresh && state.telemetry.obj("scan").str("frame_id")==grid.frame)
                            state.telemetry.obj("scan").arr("points").forEach { p->p.point()?.let {drawCircle(Color(0xFFDB8A29),1.5.dp.toPx(),screen(it))} }
                        val pose=state.telemetry.obj("pose")
                        if(state.fresh && pose.isNotEmpty()) arrow(pose.num("x",Double.NaN),pose.num("y",Double.NaN),pose.num("yaw",Double.NaN),Teal)
                        state.candidate?.takeIf {it.revision==grid.revision}?.let {arrow(it.x,it.y,it.yaw,Color(0xFF9252B5))}
                    }
                }
                if(picking && start!=null && end!=null) drawLine(Color(0xFF9252B5),start!!,end!!,4.dp.toPx())
            }
            if(grid==null) Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally) {
                Text("◈",fontSize=40.sp,color=Muted);Text("尚未加载地图",style=MaterialTheme.typography.titleMedium)
                Text("连接后选择地图，或开始建图",color=Muted)
            }
            if(picking) Surface(Modifier.align(Alignment.TopCenter).padding(8.dp),color=Color(0xFFEFE2F5),shape=MaterialTheme.shapes.small) {
                Text("按住起点并拖出船头方向，然后点击发布",Modifier.padding(10.dp),color=Color(0xFF6C378C))
            }
            if(preview==null && grid!=null && !state.fresh) Text("遥测已过期 · 仅显示地图与历史轨迹",Modifier.align(Alignment.BottomCenter).background(Color(0xFFEADCC6)).padding(8.dp))
        }
        Column(Modifier.padding(12.dp)) {
            Text(if(preview!=null) "独立预览 · 不叠加当前机器人位置" else if(centerTrack!=null)
                "蓝虚线：船中心目标 · 橙框：停车点 · 紫框：起始范围 · 绿线：历史轨迹"
                else "蓝虚线：清洗目标 · 紫框：起始范围 · 绿线：历史轨迹",style=MaterialTheme.typography.labelMedium,color=Muted)
            centerTrack?.let {
                Text(it.legend,style=MaterialTheme.typography.labelMedium,color=Muted)
                Text("橙框为各墙段常规停车点；矩形补洗可能提前结束。目标线不代表换边或避障路径。",style=MaterialTheme.typography.labelSmall,color=Muted)
            }
        }
    }
}
