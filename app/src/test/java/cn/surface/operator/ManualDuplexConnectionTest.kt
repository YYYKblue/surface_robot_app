package cn.surface.operator

import cn.surface.operator.connection.ManualSender
import cn.surface.operator.connection.RealGateway
import cn.surface.operator.core.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real local WebSocket framing: a partial downlink frame must not own the sender. */
class ManualDuplexConnectionTest {
    private fun clock()=System.nanoTime()/1_000_000L
    private fun awaitCondition(description:String,timeoutMs:Long=2500,condition:()->Boolean) {
        val deadline=System.nanoTime()+timeoutMs*1_000_000L
        while(System.nanoTime()<deadline) {
            if(condition()) return
            Thread.sleep(5)
        }
        assertTrue(description,condition())
    }

    @Test fun slowPartialDownlinkDoesNotStarveManualCadenceOrExplicitStop() {
        val peer=RawPeer()
        val incoming=CopyOnWriteArrayList<JsonObject>()
        val failures=CopyOnWriteArrayList<String>()
        val gateway=RealGateway(peer.url,{incoming.add(wireJson.parseToJsonElement(it) as JsonObject)},
            {ok,message->if(!ok) failures.add(message)})
        val stopped=CopyOnWriteArrayList<String>()
        val sender=ManualSender(::clock,{_,reason->stopped.add(reason)})
        val refreshDone=CountDownLatch(1)
        val refreshRejected=AtomicBoolean(false)
        var producer:Thread?=null
        var drip:RawPeer.Drip?=null
        try {
            sender.bind(1,gateway)
            gateway.connect()
            awaitCondition("hello received") {incoming.any {it.str("type")=="hello"}}
            assertTrue(sender.activate(1));assertTrue(sender.arm(1))
            val receivedAt=clock()
            val intent=ManualIntent("FORWARD")
            assertTrue(sender.update(1,intent,receivedAt))
            awaitCondition("first manual input") {peer.inputs().isNotEmpty()}
            // Keep the real telemetry deadline fixed. This test explicitly stops
            // before 800 ms rather than renewing freshness from a partial frame.
            producer=thread(name="test-gui-intent",isDaemon=true) {
                while(!refreshDone.await(150,TimeUnit.MILLISECONDS)) {
                    if(!sender.update(1,intent,receivedAt)) {refreshRejected.set(true);return@thread}
                }
            }
            val streamStart=clock()
            drip=peer.drip(packet("type" to "telemetry","padding" to "x".repeat(7000)))
            assertTrue(drip.started.await(1,TimeUnit.SECONDS))
            awaitCondition("seven inputs before the downlink frame finishes",700) {
                peer.inputs().count {it.at>=streamStart}>=7
            }
            assertEquals(1L,drip.finished.count)
            refreshDone.countDown();producer.join(500)
            assertFalse(refreshRejected.get())
            val stopAt=clock()
            assertTrue(sender.clear("test_operator_stop"))
            awaitCondition("explicit stop reaches peer during partial receive",250) {
                peer.messages.any {it.packet.str("op")=="manual_stop" && it.packet.str("reason")=="test_operator_stop"}
            }
            val stop=peer.messages.first {it.packet.str("op")=="manual_stop" && it.packet.str("reason")=="test_operator_stop"}
            assertTrue(stop.at-stopAt<250)
            assertEquals(1L,drip.finished.count)
            val inputs=peer.inputs()
            assertTrue(inputs.size>=7)
            assertTrue(inputs.zipWithNext().all {(a,b)->a.packet.long("seq")<b.packet.long("seq")})
            assertTrue("50 ms scheduling must stay below the gateway watchdog in this local test",
                inputs.zipWithNext().all {(a,b)->b.at-a.at<250})
            assertTrue(drip.finished.await(3,TimeUnit.SECONDS))
            assertTrue(clock()-streamStart>800)
            awaitCondition("complete downlink still delivered") {incoming.any {it.str("type")=="telemetry"}}
            assertFalse(peer.inputs().any {it.at>stop.at})
            assertTrue(failures.toString(),failures.isEmpty())
            assertTrue(stopped.toString(),stopped.isEmpty())
            peer.assertHealthy()
        } finally {
            refreshDone.countDown();producer?.join(500)
            sender.close();gateway.close();peer.close();drip?.worker?.join(500)
        }
    }

    @Test fun guiStallSendsStopAndRejectsOldIntentAfterTheGuiReturns() {
        val peer=RawPeer()
        val handshake=CountDownLatch(1)
        val gateway=RealGateway(peer.url,{raw->
            if((wireJson.parseToJsonElement(raw) as JsonObject).str("type")=="hello") handshake.countDown()
        },{_,_->})
        val stopped=CopyOnWriteArrayList<String>()
        val sender=ManualSender(::clock,{_,reason->stopped.add(reason)})
        try {
            sender.bind(2,gateway);gateway.connect()
            assertTrue(handshake.await(2,TimeUnit.SECONDS))
            assertTrue(sender.activate(2));assertTrue(sender.arm(2))
            val intent=ManualIntent("FORWARD",brush=true,thrust=true)
            assertTrue(sender.update(2,intent,clock()))
            awaitCondition("background expiry stop without any GUI callback",1000) {
                peer.messages.any {it.packet.str("op")=="manual_stop" && it.packet.str("reason")=="gui_input_expired"}
            }
            assertTrue(sender.expired)
            assertFalse(sender.armed)
            assertTrue(stopped.contains("gui_input_expired"))
            val count=peer.inputs().size
            assertTrue(count>0)
            assertFalse(sender.update(2,intent,clock()))
            Thread.sleep(150)
            assertEquals(count,peer.inputs().size)
            val stopIndex=peer.messages.indexOfFirst {it.packet.str("reason")=="gui_input_expired"}
            assertFalse(peer.messages.drop(stopIndex+1).any {it.packet.str("op")=="manual_input"})
            peer.assertHealthy()
        } finally {sender.close();gateway.close();peer.close()}
    }

    private data class Received(val at:Long,val packet:JsonObject)
    private class RawPeer:AutoCloseable {
        private val server=ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
        private val closed=AtomicBoolean(false)
        private val socket=AtomicReference<Socket>()
        private val failure=AtomicReference<Throwable>()
        val messages=CopyOnWriteArrayList<Received>()
        val url="ws://127.0.0.1:${server.localPort}/"
        private val reader=thread(name="test-raw-ws-peer",isDaemon=true) {
            try {
                val client=server.accept().also {socket.set(it);it.tcpNoDelay=true;it.soTimeout=5000}
                val input=client.getInputStream()
                val headers=StringBuilder()
                while(!headers.endsWith("\r\n\r\n")) {
                    val next=input.read();check(next>=0) {"EOF during handshake"}
                    headers.append(next.toChar());check(headers.length<8192)
                }
                val key=headers.toString().lineSequence().first {it.startsWith("Sec-WebSocket-Key:",true)}.substringAfter(':').trim()
                val accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.US_ASCII)))
                val response="HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
                client.getOutputStream().write(response.toByteArray(StandardCharsets.US_ASCII))
                client.getOutputStream().flush()
                while(!closed.get()) {
                    val first=input.read();if(first<0) break
                    val second=byte(input)
                    var size=(second and 127).toLong()
                    if(size==126L) size=((byte(input) shl 8) or byte(input)).toLong()
                    else if(size==127L) {size=0;repeat(8) {size=(size shl 8) or byte(input).toLong()}}
                    check(size in 0..65536) {"unexpected client frame size $size"}
                    val mask=if(second and 128!=0) input.readNBytes(4) else null
                    val payload=input.readNBytes(size.toInt());check(payload.size==size.toInt())
                    if(mask!=null) for(index in payload.indices) payload[index]=(payload[index].toInt() xor mask[index%4].toInt()).toByte()
                    when(first and 15) {
                        1 -> {
                            val p=wireJson.parseToJsonElement(String(payload,StandardCharsets.UTF_8)) as JsonObject
                            messages.add(Received(System.nanoTime()/1_000_000L,p))
                            if(p.str("op")=="hello") {
                                client.getOutputStream().write(frame(packet("type" to "hello","protocol" to "f1-operator-v1",
                                    "client_id" to "local-test").toString()))
                                client.getOutputStream().flush()
                            }
                        }
                        8 -> break
                    }
                }
            } catch(t:Throwable) {if(!closed.get()) failure.compareAndSet(null,t)}
        }
        data class Drip(val started:CountDownLatch,val finished:CountDownLatch,val worker:Thread)
        fun drip(packet:JsonObject):Drip {
            val started=CountDownLatch(1);val finished=CountDownLatch(1)
            val worker=thread(name="test-slow-downlink",isDaemon=true) {
                try {
                    val data=frame(packet.toString());val output=socket.get().getOutputStream()
                    for(offset in data.indices step 128) {
                        if(closed.get()) break
                        output.write(data,offset,minOf(128,data.size-offset));output.flush();started.countDown()
                        if(offset+128<data.size) Thread.sleep(25)
                    }
                } catch(t:Throwable) {if(!closed.get()) failure.compareAndSet(null,t)}
                finally {finished.countDown()}
            }
            return Drip(started,finished,worker)
        }
        fun inputs()=messages.filter {it.packet.str("op")=="manual_input"}
        fun assertHealthy() {failure.get()?.let {throw AssertionError("raw WebSocket peer failed",it)}}
        override fun close() {
            closed.set(true);socket.get()?.close();server.close();reader.join(1000)
        }
        private fun byte(input:InputStream)=input.read().also {check(it>=0) {"EOF inside frame"}}
        private fun frame(text:String):ByteArray {
            val body=text.toByteArray(StandardCharsets.UTF_8)
            require(body.size<65536)
            val header=if(body.size<126) byteArrayOf(0x81.toByte(),body.size.toByte())
                else byteArrayOf(0x81.toByte(),126,(body.size ushr 8).toByte(),body.size.toByte())
            return header+body
        }
    }
}
