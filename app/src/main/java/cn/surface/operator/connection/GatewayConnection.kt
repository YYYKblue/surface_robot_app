package cn.surface.operator.connection

import cn.surface.operator.core.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.TimeUnit

interface GatewayConnection {
    fun connect()
    fun send(value: JsonObject): Boolean
    fun close()
}

/** No application retry queue. Reconnecting creates a completely new connection. */
class RealGateway(
    private val url: String,
    private val onPacket: (String)->Unit,
    private val onStatus: (Boolean,String)->Unit,
    private val webSocketFactory: WebSocket.Factory?=null
) : GatewayConnection {
    private val http=OkHttpClient.Builder().connectTimeout(4,TimeUnit.SECONDS)
        .readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(3,TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()
    @Volatile private var socket: WebSocket?=null
    @Volatile private var open=false
    @Volatile private var closed=false
    private var backlogAt: Long?=null
    override fun connect() {
        require(url.startsWith("ws://") || url.startsWith("wss://")) { "地址必须以 ws:// 或 wss:// 开头" }
        val request=Request.Builder().url(url).build()
        socket=(webSocketFactory ?: http).newWebSocket(request,object:WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if(closed) { webSocket.cancel(); return }
                socket=webSocket; open=true
                onStatus(true,"已连接，等待协议握手")
                webSocket.send(packet("op" to "hello","client" to "f1-operator-android","version" to 1).toString())
            }
            override fun onMessage(webSocket: WebSocket,text:String) {
                if(closed) return
                if(text.length>32*1024*1024) { fail("接收数据超过 32 MiB 限制"); return }
                onPacket(text)
            }
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String) { webSocket.close(code,reason); fail("网关断开：$code") }
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) { if(!closed) fail("连接失败：${t.message}") }
        })
    }
    @Synchronized override fun send(value:JsonObject):Boolean {
        val ws=socket ?: return false
        if(!open || closed) return false
        val now=System.nanoTime()
        if(ws.queueSize()>0) {
            val operation=value.str("op")
            val mustNotWait=operation in setOf("manual_input","manual_stop") ||
                operation=="task" && value.str("action") in setOf("pause","stop","cancel")
            // Returning false alone leaves the old motion in OkHttp's queue.
            // The caller then disarms and stops sending, so a send-driven timeout
            // would never run again. Cancel now instead of queuing a safety action
            // behind an old command that might only arrive after network recovery.
            if(mustNotWait) {
                fail("发送积压，已断开控制；旧指令不会续发，请重连后核对任务状态")
                return false
            }
            if(backlogAt==null) backlogAt=now
            if(ws.queueSize()>4096 || now-backlogAt!!>150_000_000L) {
                fail("发送积压，已断开控制；不会重放旧指令"); return false
            }
        } else backlogAt=null
        return ws.send(value.toString())
    }
    @Synchronized private fun fail(reason:String) {
        if(closed) return
        open=false; closed=true; socket?.cancel(); http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll(); onStatus(false,reason)
    }
    @Synchronized override fun close() {
        closed=true; open=false; socket?.cancel(); socket=null
        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }
}
