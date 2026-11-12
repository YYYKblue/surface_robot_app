package cn.surface.operator

import cn.surface.operator.connection.UnderwaterGateway
import cn.surface.operator.connection.UnderwaterSender
import cn.surface.operator.core.*
import okhttp3.*
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** A blocked writer is deterministic here: no actual queue needs to fill over the network. */
class UnderwaterBackpressureTest {
    private class Factory:WebSocket.Factory {
        lateinit var listener:WebSocketListener
        lateinit var socket:Socket
        override fun newWebSocket(request:Request,listener:WebSocketListener):WebSocket {
            this.listener=listener
            return Socket(request).also {socket=it}
        }
        fun open()=listener.onOpen(socket,Response.Builder().request(socket.request())
            .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
    }
    private class Socket(private val request:Request):WebSocket {
        var pending=0L
        var cancelled=false
        var reject=false
        val sent=mutableListOf<String>()
        override fun request()=request
        override fun queueSize()=pending
        override fun send(text:String):Boolean {
            if(cancelled || reject) return false
            sent.add(text);return true
        }
        override fun send(bytes:ByteString)=false
        override fun close(code:Int,reason:String?)=true
        override fun cancel() {cancelled=true;pending=0}
    }

    @Test fun movementAndStopCancelBacklogImmediatelyWithoutAppendingOrRetrying() {
        for(command in listOf(UnderwaterIntent("LEFT",37,5).encode(2),underwaterPacket("op" to "manual_stop","reason" to "operator"))) {
            val factory=Factory();val status=mutableListOf<Boolean>()
            val gateway=UnderwaterGateway("ws://localhost:8766/",{}, {ok,_->status.add(ok)},factory)
            try {
                gateway.connect();factory.open()
                assertTrue(gateway.send(UnderwaterIntent("FORWARD",25,5).encode(1)))
                factory.socket.pending=1
                val before=factory.socket.sent.size
                assertFalse(gateway.send(command))
                assertTrue(factory.socket.cancelled)
                assertEquals(before,factory.socket.sent.size)
                assertEquals(listOf(true,false),status)
                factory.socket.pending=0
                assertFalse(gateway.send(command))
                factory.listener.onFailure(factory.socket,IOException("late failure"),null)
                assertEquals(listOf(true,false),status)
            } finally {gateway.close()}
        }
    }

    @Test fun synchronousTransportFailureCannotRestoreThePreviousSenderBinding() {
        val factory=Factory();var now=1000L
        val expiry=mutableListOf<String>()
        val sender=UnderwaterSender({now},{_,reason->expiry.add(reason)},autoStart=false)
        val gateway=UnderwaterGateway("ws://localhost:8766/",{}, {ok,_->
            if(!ok) sender.bind(2,null)
        },factory)
        try {
            sender.bind(1,gateway);gateway.connect();factory.open()
            assertTrue(sender.activate(1));assertTrue(sender.arm(1))
            assertTrue(sender.update(1,UnderwaterIntent("FORWARD",25,5),1250.0))
            sender.pump()
            factory.socket.pending=1;now+=50;sender.pump()
            assertTrue(factory.socket.cancelled)
            assertFalse(sender.isArmed)
            assertFalse(sender.isExpired) // The new binding owns state, not the failed old send.
            assertFalse(sender.update(1,UnderwaterIntent("FORWARD",25,5),1300.0))
            assertFalse(sender.arm(1))
            assertTrue(expiry.isEmpty())
        } finally {sender.close();gateway.close()}
    }

    @Test fun rejectedSocketSendAlsoClosesInsteadOfKeepingTheSessionArmed() {
        val factory=Factory();val status=mutableListOf<Boolean>()
        val gateway=UnderwaterGateway("ws://localhost:8766/",{}, {ok,_->status.add(ok)},factory)
        try {
            gateway.connect();factory.open();factory.socket.reject=true
            assertFalse(gateway.send(UnderwaterIntent("FORWARD",25,5).encode(1)))
            assertTrue(factory.socket.cancelled)
            assertEquals(listOf(true,false),status)
        } finally {gateway.close()}
    }
}
