package cn.surface.operator

import cn.surface.operator.connection.RealGateway
import cn.surface.operator.core.*
import okhttp3.*
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GatewayBackpressureTest {
    /** Simulates a socket whose writer cannot drain, without timing/network races. */
    private class BlockedSocketFactory:WebSocket.Factory {
        lateinit var listener:WebSocketListener
        lateinit var socket:FakeSocket
        override fun newWebSocket(request:Request,listener:WebSocketListener):WebSocket {
            this.listener=listener
            return FakeSocket(request).also {socket=it}
        }
        fun open() = listener.onOpen(socket,Response.Builder().request(socket.request())
            .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
    }
    private class FakeSocket(private val request:Request):WebSocket {
        var pendingBytes=0L
        var cancelled=false
        val sent=mutableListOf<String>()
        override fun request()=request
        override fun queueSize()=pendingBytes
        override fun send(text:String):Boolean {
            if(cancelled) return false
            sent.add(text)
            return true
        }
        override fun send(bytes:ByteString)=false
        override fun close(code:Int,reason:String?)=true
        override fun cancel() {cancelled=true;pendingBytes=0}
    }

    @Test fun manualBackpressureCancelsOldFrameEvenIfNoFurtherSendOccurs() {
        for(command in listOf(ManualIntent("FORWARD").encode(2),packet("op" to "manual_stop"))) {
            val factory=BlockedSocketFactory()
            val statuses=mutableListOf<Boolean>()
            val client=RealGateway("ws://localhost/",{}, {ok,_->statuses.add(ok)},factory)
            try {
                client.connect();factory.open()
                assertTrue(client.send(ManualIntent("FORWARD").encode(1)))
                factory.socket.pendingBytes=1 // Any outstanding frame is enough; no 150 ms wait.
                val before=factory.socket.sent.size
                assertFalse(client.send(command))
                assertTrue(factory.socket.cancelled)
                assertEquals(before,factory.socket.sent.size)
                assertEquals(listOf(true,false),statuses)
                // Network recovery must not make this connection reusable.
                factory.socket.pendingBytes=0
                assertFalse(client.send(ManualIntent("FORWARD").encode(3)))
                assertFalse(client.send(packet("op" to "manual_stop")))
                factory.listener.onFailure(factory.socket,IOException("late socket failure"),null)
                assertEquals(listOf(true,false),statuses)
            } finally {client.close()}
        }
    }

    @Test fun taskSafetyActionsDoNotWaitBehindAQueuedCommandOrClaimTheyWereSent() {
        for(action in listOf("pause","stop","cancel")) {
            val factory=BlockedSocketFactory()
            val client=RealGateway("ws://localhost/",{}, {_,_->},factory)
            try {
                client.connect();factory.open()
                assertTrue(client.send(packet("op" to "task","action" to "start",
                    "task_type" to "l","request_id" to "start")))
                factory.socket.pendingBytes=100
                val command=packet("op" to "task","action" to action,"task_type" to "l","request_id" to action)
                assertFalse(client.send(command))
                assertTrue(factory.socket.cancelled)
                assertFalse(factory.socket.sent.any {it.contains("\"request_id\":\"$action\"")})
                factory.socket.pendingBytes=0
                assertFalse(client.send(command))
            } finally {client.close()}
        }
    }

    @Test fun healthyConnectionStillSendsManualAndTaskStopsNormally() {
        val factory=BlockedSocketFactory()
        val client=RealGateway("ws://localhost/",{}, {_,_->},factory)
        try {
            client.connect();factory.open()
            assertTrue(client.send(ManualIntent("FORWARD").encode(1)))
            assertTrue(client.send(packet("op" to "manual_stop")))
            assertTrue(client.send(packet("op" to "task","action" to "stop","task_type" to "l")))
            assertFalse(factory.socket.cancelled)
            assertEquals(4,factory.socket.sent.size) // hello plus the three explicit commands
        } finally {client.close()}
    }
}
