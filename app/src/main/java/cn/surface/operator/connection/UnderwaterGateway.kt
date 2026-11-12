package cn.surface.operator.connection

import cn.surface.operator.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import okhttp3.*
import java.util.concurrent.TimeUnit

/** Separate socket and queue from the surface gateway. No commands are replayed. */
class UnderwaterGateway(
    private val url:String,
    private val onPacket:(String)->Unit,
    private val onStatus:(Boolean,String)->Unit,
    private val webSocketFactory:WebSocket.Factory?=null
):GatewayConnection {
    private val http=OkHttpClient.Builder().connectTimeout(4,TimeUnit.SECONDS)
        .readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(3,TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()
    @Volatile private var socket:WebSocket?=null
    @Volatile private var open=false
    @Volatile private var closed=false
    override fun connect() {
        require(url.startsWith("ws://") || url.startsWith("wss://")) {"地址必须以 ws:// 或 wss:// 开头"}
        socket=(webSocketFactory ?: http).newWebSocket(Request.Builder().url(url).build(),object:WebSocketListener() {
            override fun onOpen(webSocket:WebSocket,response:Response) {
                if(closed) {webSocket.cancel();return}
                socket=webSocket;open=true
                val hello=underwaterPacket("op" to "hello","version" to 1,"client" to "f1-operator-android")
                if(!webSocket.send(hello.toString())) {fail("水下握手发送失败");return}
                onStatus(true,"水下已连接，等待协议握手")
            }
            override fun onMessage(webSocket:WebSocket,text:String) {
                if(closed) return
                if(text.length>65536) {fail("水下数据超过 64 KiB 限制");return}
                onPacket(text)
            }
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String) {webSocket.close(code,reason);fail("水下网关断开：$code")}
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) {fail("水下连接失败：${t.message}")}
        })
    }
    @Synchronized override fun send(value:JsonObject):Boolean {
        val target=socket ?: return false
        if(!open || closed) return false
        if(target.queueSize()>0) {
            fail("水下发送积压，已断开；重连后须重新按键")
            return false
        }
        if(!target.send(value.toString())) {fail("水下发送失败");return false}
        return true
    }
    @Synchronized private fun fail(reason:String) {
        if(closed) return
        closed=true;open=false;socket?.cancel()
        http.dispatcher.executorService.shutdown();http.connectionPool.evictAll()
        onStatus(false,reason)
    }
    @Synchronized override fun close() {
        closed=true;open=false;socket?.cancel();socket=null
        http.dispatcher.executorService.shutdown();http.connectionPool.evictAll()
    }
}

/** In-memory protocol demonstration. No sockets, hardware or motor dynamics. */
internal class UnderwaterDemoGateway(
    private val scope:CoroutineScope,
    private val onPacket:(String)->Unit,
    private val onStatus:(Boolean,String)->Unit
):GatewayConnection {
    private val lock=Any()
    private var job:Job?=null
    private var opened=false
    private var generation=1L
    private var direction="STOP"
    private var speed=0
    private var lastStop=""
    private val client="under-android-demo-client"
    private val session=java.util.UUID.randomUUID().toString()
    override fun connect() {
        synchronized(lock) {opened=true}
        onStatus(true,"水下模拟连接中")
        onPacket(underwaterPacket("type" to "hello","session_id" to session,"client_id" to client,
            "capabilities" to kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("under_manual_v1"))),
            "drive_output" to packet("min" to 0,"max" to 100,"step" to 1,"firmware_protocol_version" to 6)).toString())
        job=scope.launch {
            var sequence=0L
            while(isActive) {
                val report=synchronized(lock) {
                    packet("connected" to true,"connection_id" to "under-demo-serial","control_generation" to generation,
                        "epoch" to 1,"state" to if(direction=="STOP") "READY" else "ACTIVE","available" to true,
                        "source" to "NANO","owner" to if(direction=="STOP") null else client,"armed" to true,"result" to "OK",
                        "reason" to "模拟就绪","last_stop_reason" to lastStop,"report_age_sec" to 0.0,
                        "control_fresh" to true,"motion_enabled" to true,"firmware_protocol_version" to 6,
                        "direction" to direction,"speed_percent" to speed,"stop_confirmed" to (direction=="STOP"))
                }
                onPacket(underwaterPacket("type" to "telemetry","session_id" to session,"sequence" to ++sequence,"state" to report).toString())
                delay(50)
            }
        }
    }
    override fun send(value:JsonObject):Boolean=synchronized(lock) {
        if(!opened) return@synchronized false
        when(value.str("op")) {
            "manual_stop" -> {direction="STOP";speed=0;generation++;lastStop=value.str("reason")}
            "manual_input" -> if(value.long("control_generation")==generation) {
                direction=value.str("direction");speed=value.long("speed_percent").toInt()
                if(speed==0 || direction=="STOP") {direction="STOP";speed=0}
            }
        }
        true
    }
    override fun close() {synchronized(lock) {opened=false;direction="STOP";speed=0};job?.cancel()}
}
