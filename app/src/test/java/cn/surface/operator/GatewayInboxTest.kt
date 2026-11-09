package cn.surface.operator

import cn.surface.operator.connection.*
import cn.surface.operator.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GatewayInboxTest {
    private fun grid(revision:Long)=GridMap(1,1,.05,Point(0.0,0.0),0.0,revision,"map",byteArrayOf(0))
    private fun map(revision:Long,session:String="s")=packet("type" to "map","map" to packet("revision" to revision),"runtime_session_id" to session).toString()
    private fun take(queue:LinkedBlockingQueue<GatewayDelivery>,type:String):GatewayDelivery {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(System.nanoTime()<deadline) {
            val value=queue.poll(100,TimeUnit.MILLISECONDS) ?: continue
            if(value.packet.str("type")==type) return value
        }
        error("Timed out waiting for $type")
    }
    @Test fun stateBurstCoalescesWhileRepliesAndDiagnosticsKeepTheirOrder() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val output=LinkedBlockingQueue<GatewayDelivery>();val failures=LinkedBlockingQueue<String>()
        val inbox=GatewayInbox(scope,{1000L},{d ->
            if(d.packet.str("type")=="hello") {entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))}
            output.add(d)
        },{_,reason,_->failures.add(reason)},Dispatchers.Default,{grid(it.long("revision"))})
        try {
            inbox.reset(1);inbox.offer(1,packet("type" to "hello").toString(),1)
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            for(i in 1..2000) {
                inbox.offer(1,packet("type" to "telemetry","sequence" to i).toString(),i.toLong())
                inbox.offer(1,packet("type" to "tracks","sequence" to i).toString(),i.toLong())
                if(i==1000) inbox.offer(1,packet("type" to "reply","request_id" to "r").toString(),1000)
                if(i==1500) inbox.offer(1,packet("type" to "diagnostics","marker" to "kept").toString(),1500)
            }
            release.countDown()
            assertEquals("r",take(output,"reply").packet.str("request_id"))
            assertEquals("kept",take(output,"diagnostics").packet.str("marker"))
            val telemetry=take(output,"telemetry")
            assertEquals(2000L,telemetry.packet.long("sequence"));assertEquals(2000L,telemetry.receivedAt)
            assertTrue(telemetry.packet.obj("_transport").long("replaced")>=3998)
            assertEquals(2000L,take(output,"tracks").packet.long("sequence"));assertTrue(failures.isEmpty())
        } finally {release.countDown();inbox.close();scope.cancel()}
    }
    @Test fun mapWorkNeverBlocksStateAndOneRunningPlusLatestPendingCannotStarve() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        val output=LinkedBlockingQueue<GatewayDelivery>();val decodes=AtomicInteger()
        val inbox=GatewayInbox(scope,{1000L},output::add,{_,reason,_->error(reason)},Dispatchers.Default,{p ->
            decodes.incrementAndGet()
            if(p.long("revision")==1L) {started.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))}
            grid(p.long("revision"))
        })
        try {
            inbox.reset(1);inbox.offer(1,packet("type" to "hello").toString(),1);take(output,"hello")
            inbox.offer(1,map(1),2);assertTrue(started.await(5,TimeUnit.SECONDS))
            for(i in 2L..100L) inbox.offer(1,map(i),i+2)
            inbox.offer(1,packet("type" to "telemetry").toString(),150)
            inbox.offer(1,packet("type" to "reply","request_id" to "stop").toString(),151)
            assertEquals(150L,take(output,"telemetry").receivedAt)
            assertEquals("stop",take(output,"reply").packet.str("request_id"))
            release.countDown()
            assertEquals(1L,take(output,"map").grid!!.revision)
            assertEquals(100L,take(output,"map").grid!!.revision)
            assertEquals(2,decodes.get())
        } finally {release.countDown();inbox.close();scope.cancel()}
    }
    @Test fun clearAndConnectionResetRejectOldDecodeAndPreviewAckPrecedesPixels() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        val output=LinkedBlockingQueue<GatewayDelivery>()
        val inbox=GatewayInbox(scope,{1000L},output::add,{_,reason,_->error(reason)},Dispatchers.Default,{p ->
            if(p.long("revision")==1L) {started.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))}
            grid(p.long("revision"))
        })
        try {
            inbox.reset(1);inbox.offer(1,packet("type" to "hello").toString(),1);take(output,"hello")
            inbox.offer(1,map(1),2);assertTrue(started.await(5,TimeUnit.SECONDS))
            inbox.offer(1,packet("type" to "map","map" to null,"runtime_session_id" to "next").toString(),3)
            assertNull(take(output,"map").grid)
            inbox.offer(1,packet("type" to "reply","request_id" to "preview","success" to true,
                "preview" to packet("revision" to 9)).toString(),4)
            assertEquals("preview",take(output,"reply").packet.str("request_id"))
            assertTrue(output.isEmpty())
            release.countDown()
            assertEquals(9L,take(output,"decoded_preview").grid!!.revision)
            assertTrue(output.isEmpty())
            inbox.reset(2);inbox.offer(1,packet("type" to "telemetry","old" to true).toString(),5)
            inbox.offer(2,packet("type" to "hello").toString(),6);assertEquals(2,take(output,"hello").epoch)
            assertTrue(output.isEmpty())
        } finally {release.countDown();inbox.close();scope.cancel()}
    }
    @Test fun delayedClearCannotEraseANewerMapAtTheSameReceiveMillisecond() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        val output=LinkedBlockingQueue<GatewayDelivery>();val seen=CopyOnWriteArrayList<GatewayDelivery>()
        val inbox=GatewayInbox(scope,{1000L},{seen.add(it);output.add(it)},{_,reason,_->error(reason)},Dispatchers.Default,
            decodeMap={grid(it.long("revision"))},parsePacket={raw ->
                val p=wireJson.parseToJsonElement(raw) as JsonObject
                if(p.str("block")=="parser") {started.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))}
                p
            })
        try {
            inbox.reset(1);inbox.offer(1,packet("type" to "hello").toString(),1);take(output,"hello")
            inbox.offer(1,packet("type" to "reply","block" to "parser").toString(),2)
            assertTrue(started.await(5,TimeUnit.SECONDS))
            inbox.offer(1,packet("type" to "map","map" to null,"runtime_session_id" to "new").toString(),10)
            inbox.offer(1,map(3,"new"),10)
            assertEquals(3L,take(output,"map").grid!!.revision)
            release.countDown();inbox.offer(1,packet("type" to "telemetry").toString(),11)
            take(output,"telemetry")
            assertEquals(1,seen.count {it.packet.str("type")=="map"})
        } finally {release.countDown();inbox.close();scope.cancel()}
    }
    @Test fun evenPreviewJsonParsingIsOffTheControlLane() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        val output=LinkedBlockingQueue<GatewayDelivery>()
        val inbox=GatewayInbox(scope,{1000L},output::add,{_,reason,_->error(reason)},Dispatchers.Default,
            decodeMap={grid(it.long("revision"))},parsePacket={raw ->
                if(raw.contains("slow_preview_json")) {started.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))}
                wireJson.parseToJsonElement(raw) as JsonObject
            })
        try {
            inbox.reset(1);inbox.offer(1,packet("type" to "hello").toString(),1);take(output,"hello")
            inbox.offer(1,packet("type" to "reply","request_id" to "p","success" to true,"preview" to
                packet("revision" to 9,"data" to JsonArray(listOf(JsonPrimitive("slow_preview_json"))))).toString(),2)
            assertEquals("p",take(output,"reply").packet.str("request_id"))
            assertTrue(started.await(5,TimeUnit.SECONDS))
            inbox.offer(1,packet("type" to "telemetry").toString(),3)
            assertEquals(3L,take(output,"telemetry").receivedAt)
            release.countDown();assertEquals(9L,take(output,"decoded_preview").grid!!.revision)
        } finally {release.countDown();inbox.close();scope.cancel()}
    }
}
