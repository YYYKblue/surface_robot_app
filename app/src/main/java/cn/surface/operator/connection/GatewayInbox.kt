package cn.surface.operator.connection

import cn.surface.operator.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*

data class GatewayDelivery(val epoch:Int,val packet:JsonObject,val grid:GridMap?,val receivedAt:Long,val order:Long)

/** Bounded state mailboxes; map parsing never occupies the state/control consumer. */
class GatewayInbox(
    scope:CoroutineScope,
    private val clock:()->Long,
    private val deliver:(GatewayDelivery)->Unit,
    private val report:(Int,String,Boolean)->Unit,
    private val deliveryDispatcher:CoroutineDispatcher=Dispatchers.Main.immediate,
    private val decodeMap:(JsonObject)->GridMap={GridMap.parse(it).also {grid->grid.prepareImage()}},
    private val parsePacket:(String)->JsonObject={wireJson.parseToJsonElement(it) as? JsonObject ?: error("消息不是对象")}
) {
    private data class Raw(val epoch:Int,val text:String,val receivedAt:Long,val order:Long,val controlText:String?=null)
    private data class MapWork(val raw:Raw,val generation:Long,val preview:Boolean=false)
    private val lock=Any()
    private val reliable=ArrayDeque<Raw>()
    private val latest=mutableMapOf<String,Raw>()
    private var reliableChars=0L
    private val ready=Channel<Unit>(Channel.CONFLATED)
    private val mapsReady=Channel<Unit>(Channel.CONFLATED)
    private var epoch=0
    private var sequence=0L
    private var replaced=0L
    private var mapGeneration=0L
    private var mapSession=""
    private var lastMapDeliveredOrder=-1L
    private var pendingMap:MapWork?=null
    private var pendingPreview:MapWork?=null
    private var closed=false
    private var handshake=CompletableDeferred<Boolean>()
    private val parser=scope.launch(Dispatchers.Default) {
        for(unused in ready) {
            val batch=synchronized(lock) {
                (reliable.toList()+latest.values).sortedBy {it.order}.also {
                    reliable.clear();latest.clear();reliableChars=0
                }
            }
            for(raw in batch) {
                if(!current(raw.epoch)) continue
                try {
                    val start=clock()
                    val p=parsePacket(raw.controlText ?: raw.text)
                    val preview=raw.controlText!=null
                    // A preview image may be expensive. Deliver its acknowledgement first.
                    val control=if(preview) JsonObject(p-"preview") else p
                    publish(raw,control,null,clock()-start)
                    if(p.str("type")=="hello") synchronized(lock) {
                        if(currentLocked(raw.epoch)) handshake.complete(true)
                    }
                    if(preview && p.flag("success") && !p.flag("pending")) {
                        synchronized(lock) {
                            if(currentLocked(raw.epoch)) {
                                if(pendingPreview!=null) replaced++
                                pendingPreview=MapWork(raw,mapGeneration,true)
                                mapsReady.trySend(Unit)
                            }
                        }
                    }
                } catch(e:Exception) {
                    if(e is CancellationException) throw e
                    if(current(raw.epoch)) withContext(deliveryDispatcher) {report(raw.epoch,"接收数据无效：${e.message}",true)}
                }
            }
        }
    }
    private val mapper=scope.launch(Dispatchers.Default) {
        for(unused in mapsReady) {
            while(true) {
                val work=synchronized(lock) {
                    val map=pendingMap;val preview=pendingPreview
                    when {
                        preview!=null && (map==null || preview.raw.order<map.raw.order) -> preview.also {pendingPreview=null}
                        map!=null -> map.also {pendingMap=null}
                        else -> null
                    }
                } ?: break
                val raw=work.raw
                if(!validMap(work)) continue
                try {
                    val start=clock()
                    val p=parsePacket(raw.text)
                    val parseMs=clock()-start
                    val mapObject=if(work.preview) p.obj("preview") else
                        (if("map" in p) p["map"] else p) as? JsonObject ?: error("地图内容无效")
                    val grid=decodeMap(mapObject)
                    val small=if(work.preview) packet("type" to "decoded_preview","request_id" to p["request_id"],"name" to p["name"])
                        else JsonObject(p.filterKeys {it !in setOf("map","data","data_zlib_base64")} +
                            if(p.str("runtime_session_id").isEmpty() && mapObject.str("runtime_session_id").isNotEmpty())
                                mapOf("runtime_session_id" to JsonPrimitive(mapObject.str("runtime_session_id"))) else emptyMap())
                    if(validMap(work)) publish(raw,small,grid,parseMs,clock()-start,work)
                } catch(e:Exception) {
                    if(e is CancellationException) throw e
                    if(validMap(work)) withContext(deliveryDispatcher) {
                        report(raw.epoch,if(work.preview) "地图预览解码失败：${e.message}" else "地图解码失败：${e.message}",false)
                    }
                }
            }
        }
    }
    private fun currentLocked(value:Int)=!closed && epoch==value
    private fun current(value:Int)=synchronized(lock) {currentLocked(value)}
    private fun validMap(work:MapWork)=synchronized(lock) {
        currentLocked(work.raw.epoch) && (work.preview || work.generation==mapGeneration)
    }
    private suspend fun publish(raw:Raw,p:JsonObject,grid:GridMap?,parseMs:Long,decodeMs:Long?=null,work:MapWork?=null) {
        if(work!=null) {
            val gate=synchronized(lock) {if(currentLocked(raw.epoch)) handshake else null} ?: return
            if(!gate.await()) return
        }
        withContext(deliveryDispatcher) {
            fun deliverNow() {
                val transport=packet("wire_chars" to raw.text.length,"parse_ms" to parseMs,
                    "gui_queue_ms" to (clock()-raw.receivedAt).coerceAtLeast(0),
                    "replaced" to synchronized(lock) {replaced},"map_decode_ms" to decodeMs)
                deliver(GatewayDelivery(raw.epoch,JsonObject(p+mapOf("_transport" to transport)),grid,raw.receivedAt,raw.order))
            }
            if(p.str("type")=="map") {
                val accepted=synchronized(lock) {
                    if(!currentLocked(raw.epoch) || work!=null && !validMap(work)) return@synchronized false
                    // A clear and its following image can share the same millisecond.
                    // Check wire order, so a delayed clear cannot erase the newer image.
                    if(raw.order<lastMapDeliveredOrder) return@synchronized false
                    lastMapDeliveredOrder=raw.order
                    true
                }
                // Production delivery uses the serial main dispatcher. Never hold
                // the socket-facing lock while UI reduction/logging invokes callbacks.
                if(accepted) deliverNow()
            } else if(current(raw.epoch) && (work==null || validMap(work))) deliverNow()
        }
    }
    fun reset(nextEpoch:Int) = synchronized(lock) {
        handshake.complete(false);handshake=CompletableDeferred()
        epoch=nextEpoch;mapGeneration++;mapSession="";lastMapDeliveredOrder=-1
        reliable.clear();latest.clear();reliableChars=0;pendingMap=null;pendingPreview=null
    }
    fun offer(value:Int,text:String,receivedAt:Long) {
        if(!current(value)) return
        val envelope=peekEnvelope(text)
        var overflow=false
        synchronized(lock) {
            if(!currentLocked(value)) return
            // Only the small reply envelope enters the control parser. The full
            // legacy preview JSON array is parsed by the independent map worker.
            val controlText=if(envelope.kind=="reply" && envelope.previewStart>=0)
                text.replaceRange(envelope.previewStart,envelope.previewEnd,"null") else null
            val raw=Raw(value,text,receivedAt,++sequence,controlText)
            if(envelope.kind=="map") {
                if(envelope.mapNull || envelope.session.isNotEmpty() && envelope.session!=mapSession) {
                    mapGeneration++;pendingMap=null
                    if(envelope.session.isNotEmpty()) mapSession=envelope.session
                }
                if(!envelope.mapNull) {
                    if(pendingMap!=null) replaced++
                    pendingMap=MapWork(raw,mapGeneration)
                    mapsReady.trySend(Unit)
                    return
                }
            }
            if(envelope.kind in setOf("telemetry","tracks","map")) {
                if(latest.put(envelope.kind,raw)!=null) replaced++
            } else {
                if(reliable.size>=64 || reliableChars+text.length>64L*1024*1024) overflow=true
                else {reliable.addLast(raw);reliableChars+=text.length}
            }
            ready.trySend(Unit)
        }
        if(overflow) report(value,"操作消息接收积压，重新连接以同步状态",true)
    }
    fun close() = synchronized(lock) {
        handshake.complete(false)
        closed=true;reliable.clear();latest.clear();pendingMap=null;pendingPreview=null
        ready.close();mapsReady.close();parser.cancel();mapper.cancel()
    }
}

private data class Envelope(val kind:String="",val session:String="",val mapNull:Boolean=false,
                            val previewStart:Int=-1,val previewEnd:Int=-1)

/** Read only top-level routing fields, without allocating the legacy map's large JSON array. */
private fun peekEnvelope(text:String):Envelope {
    var i=0
    fun whitespace() {while(i<text.length && text[i].isWhitespace()) i++}
    fun stringEnd():Int {
        val start=i++
        while(i<text.length) {
            val c=text[i++]
            if(c=='\\') {if(i<text.length) i++}
            else if(c=='"') return i
        }
        return start
    }
    fun skipValue() {
        if(i>=text.length) return
        if(text[i]=='"') {stringEnd();return}
        if(text[i]=='{' || text[i]=='[') {
            var depth=0
            while(i<text.length) {
                when(text[i]) {
                    '"' -> {stringEnd();continue}
                    '{','[' -> depth++
                    '}',']' -> {depth--;if(depth==0) {i++;return}}
                };i++
            }
        } else while(i<text.length && text[i]!=',' && text[i]!='}') i++
    }
    return try {
        whitespace();if(i>=text.length || text[i++]!='{') return Envelope()
        var kind="";var session="";var mapNull=false;var previewStart=-1;var previewEnd=-1
        while(i<text.length) {
            whitespace();if(i>=text.length || text[i]=='}') break
            if(text[i]!='"') return Envelope()
            val begin=i;val end=stringEnd();if(end<=begin) return Envelope()
            val key=(wireJson.parseToJsonElement(text.substring(begin,end)) as JsonPrimitive).content
            whitespace();if(i>=text.length || text[i++]!=':') return Envelope()
            whitespace();val valueStart=i;skipValue()
            if(key in setOf("type","runtime_session_id","session_id")) {
                val value=(wireJson.parseToJsonElement(text.substring(valueStart,i)) as? JsonPrimitive)?.contentOrNull.orEmpty()
                if(key=="type") kind=value else if(key=="runtime_session_id" || session.isEmpty()) session=value
            } else if(key=="map") {
                var valueEnd=i
                while(valueEnd>valueStart && text[valueEnd-1].isWhitespace()) valueEnd--
                mapNull=valueEnd-valueStart==4 && text.regionMatches(valueStart,"null",0,4)
            } else if(key=="preview" && valueStart<text.length && text[valueStart]=='{') {
                previewStart=valueStart;previewEnd=i
            }
            whitespace();if(i<text.length && text[i]==',') i++ else break
        }
        Envelope(kind,session,mapNull,previewStart,previewEnd)
    } catch(_:Exception) {Envelope()}
}
