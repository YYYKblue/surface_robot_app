package cn.surface.operator.ui

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
import kotlin.math.*

private data class MapViewKey(val preview:GridMap?,val session:String,val frame:String?)
private data class RenderedMap(val grid:GridMap,val image:ImageBitmap,val key:MapViewKey)
private class ReferenceKey(val value:Any) {
    override fun equals(other:Any?)=other is ReferenceKey && other.value===value
    override fun hashCode()=System.identityHashCode(value)
}

/** Retain projected paths between pose/clock updates, rebuilding only when history or view changes. */
private class ScreenTrackCache {
    private var owner:List<List<Point>>?=null
    private var key:List<Any>?=null
    var paths:List<Path> = emptyList(); private set
    var dots:List<Offset> = emptyList(); private set
    fun update(lines:List<List<Point>>,grid:GridMap,width:Float,height:Float,zoom:Float,pan:Offset,tr:MapTransform) {
        val nextKey=listOf(grid.width,grid.height,grid.resolution,grid.origin,grid.yaw,width,height,zoom,pan)
        if(owner===lines && key==nextKey) return
        owner=lines;key=nextKey
        val nextPaths=mutableListOf<Path>();val nextDots=mutableListOf<Offset>()
        for(line in lines) {
            fun screen(p:Point)=tr.worldToScreen(p).let {Offset(it.x.toFloat(),it.y.toFloat())}
            if(line.size==1) nextDots.add(screen(line.first()))
            else if(line.isNotEmpty()) nextPaths.add(Path().apply {
                val start=screen(line.first());moveTo(start.x,start.y)
                for(i in 1 until line.size) {val p=screen(line[i]);lineTo(p.x,p.y)}
            })
        }
        paths=nextPaths;dots=nextDots
    }
}

@Composable
fun MapPanel(state:OperatorState,onPose:(Pose?)->Unit,modifier:Modifier=Modifier,preview:GridMap?=null) {
    val incomingGrid=preview ?: state.map
    // Mapping revisions may arrive every two seconds. Only a new map session resets the view.
    val viewKey=MapViewKey(preview,if(preview==null) state.mapSession.ifEmpty {state.localization.str("map_id")} else "",incomingGrid?.frame)
    var zoom by remember(viewKey) { mutableFloatStateOf(1f) }
    var pan by remember(viewKey) { mutableStateOf(Offset.Zero) }
    var pixels by remember { mutableStateOf(IntSize(1,1)) }
    var showTrack by remember { mutableStateOf(true) }
    val rendered by produceState<RenderedMap?>(null,incomingGrid,viewKey) {
        if(incomingGrid==null) {value=null;return@produceState}
        if(value?.key!=viewKey) value=null
        // The receive worker normally prepares this image already. This fallback also
        // handles directly constructed mock/test grids; keep the old image and its
        // geometry together until the replacement is ready instead of flashing blank.
        value=withContext(Dispatchers.Default) {RenderedMap(incomingGrid,incomingGrid.prepareImage().asImageBitmap(),viewKey)}
    }
    val display=rendered?.takeIf {incomingGrid!=null && it.key==viewKey}
    val grid=display?.grid
    val mapMatchesRuntime=state.mapMatchesRuntime
    val task=state.task
    val actual=task.obj("actual_track")
    val trackLines=remember(ReferenceKey(actual),grid?.frame,task.str("map_id"),task.str("run_id"),state.matchingTaskMap,mapMatchesRuntime,showTrack,preview!=null) {
        if(grid!=null && preview==null && showTrack && state.matchingTaskMap && mapMatchesRuntime) actualTrackPolylines(task,grid) else emptyList()
    }
    val screenTracks=remember {ScreenTrackCache()}
    val transform=grid?.let { MapTransform(it,pixels.width.toDouble(),pixels.height.toDouble(),zoom.toDouble(),Point(pan.x.toDouble(),pan.y.toDouble())) }
    val currentTransform by rememberUpdatedState(transform)
    val currentPoseCallback by rememberUpdatedState(onPose)
    val picking=preview==null && state.picking && grid===incomingGrid
    val livePose=preview==null && state.fresh && mapMatchesRuntime
    Column(modifier.background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if(preview!=null) "保存地图预览" else "现场地图",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
            TextButton(onClick={zoom=1f;pan=Offset.Zero}) { Text("全图") }
            TextButton(onClick={
                val p=state.telemetry.obj("pose")
                if(livePose && p.isNotEmpty() && transform!=null) {
                    val at=transform.worldToScreen(Point(p.num("x"),p.num("y")))
                    pan+=Offset(pixels.width/2f-at.x.toFloat(),pixels.height/2f-at.y.toFloat())
                }
            },enabled=livePose && grid!=null && state.telemetry.obj("pose").isNotEmpty()) { Text("船体回中") }
        }
        Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            if(preview==null) FilterChip(showTrack,{showTrack=!showTrack},label={Text("历史轨迹")})
            Spacer(Modifier.weight(1f))
            Text("${"%.1f".format(zoom)}×",color=Muted)
            TextButton(onClick={zoom=(zoom/1.4f).coerceAtLeast(.5f)}) { Text("−") }
            TextButton(onClick={zoom=(zoom*1.4f).coerceAtMost(10f)}) { Text("＋") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0xFFF0F4F6))) {
            var start by remember(grid,picking) { mutableStateOf<Offset?>(null) }
            var end by remember(grid,picking) { mutableStateOf<Offset?>(null) }
            Canvas(Modifier.fillMaxSize().semantics {
                contentDescription=if(preview!=null) "独立地图预览" else "现场地图：实际轨迹 ${trackLines.size} 段"
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
                val tr=grid?.let {MapTransform(it,size.width.toDouble(),size.height.toDouble(),zoom.toDouble(),Point(pan.x.toDouble(),pan.y.toDouble()))}
                if(tr!=null) {
                    drawImage(display.image,dstOffset=IntOffset(tr.left.roundToInt(),(tr.bottom-grid.height*grid.resolution*tr.scale).roundToInt()),
                        dstSize=IntSize((grid.width*grid.resolution*tr.scale).roundToInt().coerceAtLeast(1),(grid.height*grid.resolution*tr.scale).roundToInt().coerceAtLeast(1)),filterQuality=FilterQuality.None)
                    fun arrow(x:Double,y:Double,yaw:Double,color:Color) {
                        if(!x.isFinite()||!y.isFinite()||!yaw.isFinite()) return
                        val p=tr.worldToScreen(Point(x,y)).let {Offset(it.x.toFloat(),it.y.toFloat())}
                        val a=yaw-grid.yaw;val length=19.dp.toPx()
                        val path=Path().apply {
                            moveTo(p.x+length*cos(a).toFloat(),p.y-length*sin(a).toFloat())
                            lineTo(p.x-length*.65f*cos(a+.75).toFloat(),p.y+length*.65f*sin(a+.75).toFloat())
                            lineTo(p.x-length*.65f*cos(a-.75).toFloat(),p.y+length*.65f*sin(a-.75).toFloat());close()
                        };drawPath(path,color);drawPath(path,Color.White,style=Stroke(2f))
                    }
                    if(preview==null) {
                        screenTracks.update(trackLines,grid,size.width,size.height,zoom,pan,tr)
                        screenTracks.paths.forEach {drawPath(it,Teal,style=Stroke(2.5.dp.toPx()))}
                        screenTracks.dots.forEach {drawCircle(Teal,1.5.dp.toPx(),it)}
                        val pose=state.telemetry.obj("pose")
                        if(livePose && pose.isNotEmpty()) arrow(pose.num("x",Double.NaN),pose.num("y",Double.NaN),pose.num("yaw",Double.NaN),Teal)
                        state.candidate?.takeIf {it.revision==grid.revision}?.let {arrow(it.x,it.y,it.yaw,Color(0xFF9252B5))}
                    }
                }
                if(picking && start!=null && end!=null) drawLine(Color(0xFF9252B5),start!!,end!!,4.dp.toPx())
            }
            if(incomingGrid==null) Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally) {
                Text("◈",fontSize=40.sp,color=Muted);Text("尚未加载地图",style=MaterialTheme.typography.titleMedium)
                Text("连接后选择地图，或开始建图",color=Muted)
            }
            if(picking) Surface(Modifier.align(Alignment.TopCenter).padding(8.dp),color=Color(0xFFEFE2F5),shape=MaterialTheme.shapes.small) {
                Text("按住起点并拖出船头方向，然后点击发布",Modifier.padding(10.dp),color=Color(0xFF6C378C))
            }
            if(preview==null && grid!=null && !state.fresh) Text("遥测已过期 · 仅显示地图与历史轨迹",Modifier.align(Alignment.BottomCenter).background(Color(0xFFEADCC6)).padding(8.dp))
        }
        Text(if(preview!=null) "独立预览 · 不叠加当前机器人位置" else "绿色箭头：机器人位置 · 绿线：实际轨迹",
            Modifier.padding(12.dp),style=MaterialTheme.typography.labelMedium,color=Muted)
    }
}
