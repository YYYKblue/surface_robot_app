package cn.surface.operator.core

import android.graphics.Bitmap
import kotlinx.serialization.json.*
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.*

val EMPTY = JsonObject(emptyMap())
val wireJson = Json { ignoreUnknownKeys = true }
fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: EMPTY
fun JsonObject.arr(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
fun JsonObject.str(key: String, default: String = "") = (this[key] as? JsonPrimitive)?.contentOrNull ?: default
fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
fun JsonObject.num(key: String, default: Double = 0.0) = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: default
fun JsonObject.long(key: String, default: Long = 0) = (this[key] as? JsonPrimitive)?.longOrNull ?: default
fun packet(vararg values: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k,v) in values) put(k, when(v) {
        null -> JsonNull
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    })
}
data class Point(val x: Double, val y: Double)
fun JsonElement.point(): Point? {
    val p = this as? JsonArray ?: return null
    if (p.size != 2) return null
    val x = (p[0] as? JsonPrimitive)?.doubleOrNull ?: return null
    val y = (p[1] as? JsonPrimitive)?.doubleOrNull ?: return null
    return if (x.isFinite() && y.isFinite()) Point(x,y) else null
}
fun JsonArray.polylines(): List<List<Point>> {
    val result = mutableListOf<List<Point>>()
    var line = mutableListOf<Point>()
    for (v in this) {
        val p = v.point()
        if (p != null) line.add(p) else { if (line.isNotEmpty()) result.add(line); line = mutableListOf() }
    }
    if (line.isNotEmpty()) result.add(line)
    return result
}
data class Pose(val x: Double, val y: Double, val yaw: Double, val revision: Long)
class GridMap(val width: Int, val height: Int, val resolution: Double, val origin: Point,
              val yaw: Double, val revision: Long, val frame: String, val cells: ByteArray) {
    @Volatile private var image: Bitmap? = null

    /** Called by the map worker before publication. No per-cell drawing on the UI thread. */
    @Synchronized fun prepareImage(): Bitmap {
        image?.let { return it }
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val row=IntArray(width)
        for(y in 0 until height) {
            for(x in 0 until width) {
                val cell=cells[(height-1-y)*width+x].toInt()
                row[x]=when {cell<0 -> 0xFFEEF2F3.toInt(); cell>50 -> 0xFF536875.toInt(); else -> 0xFFFFFFFF.toInt()}
            }
            bitmap.setPixels(row,0,width,0,y,width,1)
        }
        image=bitmap
        return bitmap
    }

    companion object {
        const val MAX_CELLS = 4_000_000
        fun parse(raw: JsonObject): GridMap {
            val w = raw.long("width"); val h = raw.long("height")
            require(w in 1..8192 && h in 1..8192 && w*h <= MAX_CELLS) { "地图尺寸超出支持范围（最多 400 万栅格）" }
            val r = raw.num("resolution")
            require(r > 0 && r <= 100) { "地图分辨率无效" }
            val o = raw.obj("origin")
            for (k in listOf("x", "y", "yaw")) require(o.num(k, Double.NaN).isFinite()) { "地图原点无效" }
            val size=(w*h).toInt()
            val cells=when(raw.str("encoding")) {
                "indexed8_zlib" -> decodeIndexed(raw.str("data_zlib_base64"),size)
                "" -> {
                    val data=raw.arr("data")
                    require(data.size==size) { "地图数据长度不匹配" }
                    ByteArray(size) { i ->
                        val v=(data[i] as? JsonPrimitive)?.intOrNull
                        require(v!=null && v in -1..100) { "地图包含无效栅格" }; v.toByte()
                    }
                }
                else -> throw IllegalArgumentException("不支持的地图编码：${raw.str("encoding")}")
            }
            return GridMap(w.toInt(),h.toInt(),r,Point(o.num("x"),o.num("y")),o.num("yaw"),raw.long("revision"),raw.str("frame_id"),cells)
        }

        private fun decodeIndexed(encoded:String,size:Int):ByteArray {
            // Even an uncompressed zlib stream needs only a small framing overhead.
            require(encoded.length<=((size.toLong()+65536)*4/3+4)) { "地图压缩数据过大" }
            val compressed=try {Base64.getDecoder().decode(encoded)}
                catch(e:IllegalArgumentException) {throw IllegalArgumentException("地图 Base64 无效",e)}
            val decoder=Inflater()
            try {
                decoder.setInput(compressed)
                val pixels=ByteArray(size+1)
                var count=0
                while(!decoder.finished() && count<pixels.size) {
                    val n=decoder.inflate(pixels,count,pixels.size-count)
                    require(n>0 || decoder.finished()) { "地图压缩数据不完整或需要字典" }
                    count+=n
                }
                require(count==size && decoder.finished() && decoder.remaining==0) { "地图压缩数据与尺寸不一致" }
                return ByteArray(size) {i -> when(pixels[i].toInt()) {
                    0 -> (-1).toByte()
                    1 -> 0
                    2 -> 100
                    else -> throw IllegalArgumentException("地图包含无效颜色索引")
                }}
            } catch(e:DataFormatException) {
                throw IllegalArgumentException("地图 zlib 数据无效",e)
            } finally {decoder.end()}
        }
    }
}
/** One invertible transform for map pixels, world overlays and pose selection. */
class MapTransform(val map: GridMap, width: Double, height: Double,
                   zoom: Double = 1.0, pan: Point = Point(0.0,0.0)) {
    val scale = min((width-48).coerceAtLeast(1.0)/(map.width*map.resolution),
                    (height-48).coerceAtLeast(1.0)/(map.height*map.resolution))*zoom
    val left = width/2-map.width*map.resolution*scale/2+pan.x
    val bottom = height/2+map.height*map.resolution*scale/2+pan.y
    fun worldToScreen(p: Point): Point {
        val dx=p.x-map.origin.x; val dy=p.y-map.origin.y
        return Point(left+(cos(map.yaw)*dx+sin(map.yaw)*dy)*scale,
                     bottom-(-sin(map.yaw)*dx+cos(map.yaw)*dy)*scale)
    }
    fun screenToWorld(p: Point, bounded: Boolean = false): Point? {
        val x=(p.x-left)/scale; val y=(bottom-p.y)/scale
        if(bounded && (x < 0 || y < 0 || x >= map.width*map.resolution || y >= map.height*map.resolution)) return null
        return Point(map.origin.x+cos(map.yaw)*x-sin(map.yaw)*y,map.origin.y+sin(map.yaw)*x+cos(map.yaw)*y)
    }
}
data class ManualIntent(val direction: String="STOP", val speed: Int=25, val mode: String="BOTH",
                        val brush: Boolean=false, val brushPercent: Int=25,
                        val thrust: Boolean=false, val thrustPercent: Int=25) {
    val active get() = direction != "STOP" || brush || thrust
    fun cleared() = copy(direction="STOP",brush=false,thrust=false)
    fun encode(seq: Long): JsonObject {
        require(direction in setOf("STOP","FORWARD","REVERSE","LEFT","RIGHT"))
        require(speed in setOf(25,50,75) && mode in setOf("FRONT","BOTH","BACK"))
        require(brushPercent in -100..100 && thrustPercent in -100..100 && seq >= 0)
        return packet("op" to "manual_input", "seq" to seq, "direction" to direction,
            "speed_percent" to speed,"mode" to mode,"brush_enabled" to brush,
            "brush_percent" to if(brush) brushPercent else 0,"thrust_enabled" to thrust,
            "thrust_percent" to if(thrust) thrustPercent else 0)
    }
}
