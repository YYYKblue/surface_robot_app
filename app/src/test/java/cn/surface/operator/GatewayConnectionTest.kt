package cn.surface.operator

import cn.surface.operator.connection.RealGateway
import cn.surface.operator.core.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class GatewayConnectionTest {
    @Test fun actualWebsocketHandshakeAndCommandRoundTrip() {
        val server=MockWebServer();val received=LinkedBlockingQueue<String>();val incoming=LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
            override fun onMessage(webSocket:WebSocket,text:String) {
                received.add(text)
                webSocket.send(packet("type" to "reply","request_id" to "t1","success" to true,"message" to "fixture only").toString())
            }
        }))
        server.start()
        val client=RealGateway(server.url("/").toString().replace("http:","ws:"),{incoming.add(it)},{_,_->})
        try {
            client.connect()
            val hello=received.poll(5,TimeUnit.SECONDS)
            assertNotNull(hello);assertTrue(hello!!.contains("f1-operator-android"))
            assertTrue(client.send(packet("op" to "task","action" to "stop","task_type" to "rectangle","request_id" to "t1")))
            assertTrue(received.poll(5,TimeUnit.SECONDS)!!.contains("\"stop\""))
            assertNotNull(incoming.poll(5,TimeUnit.SECONDS))
            client.close()
            assertFalse(client.send(ManualIntent("FORWARD").encode(1)))
        } finally {client.close();server.shutdown()}
    }
}
