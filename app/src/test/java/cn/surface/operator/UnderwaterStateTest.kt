package cn.surface.operator

import cn.surface.operator.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UnderwaterStateTest {
    private fun hello()=underwaterPacket("type" to "hello","session_id" to "server1","client_id" to "client1",
        "capabilities" to JsonArray(listOf(JsonPrimitive("under_manual_v1"))),
        "drive_output" to packet("min" to 0,"max" to 100,"step" to 1,"firmware_protocol_version" to 6))
    private fun report()=packet("connected" to true,"connection_id" to "serial1","control_generation" to 7,
        "epoch" to 4,"state" to "READY","available" to true,"source" to "NANO","owner" to null,
        "armed" to true,"result" to "OK","reason" to "ready","report_age_sec" to 0.0,
        "control_fresh" to true,"motion_enabled" to true,"firmware_protocol_version" to 6,
        "direction" to "STOP","speed_percent" to 0)
    private fun telemetry(r:JsonObject=report(),seq:Any=1)=underwaterPacket("type" to "telemetry","session_id" to "server1","sequence" to seq,"state" to r)
    private fun ready():UnderwaterState {
        val s=UnderwaterReducer.hello(UnderwaterState(visible=true),hello(),1000)!!
        return UnderwaterReducer.telemetry(s,telemetry(),1000,1000)
    }
    private fun changed(r:JsonObject,vararg entries:Pair<String,Any?>)=JsonObject(r+packet(*entries))

    @Test fun handshakeNeedsExactProtocolDeviceCapabilityAndNumericRange() {
        assertNotNull(UnderwaterReducer.hello(UnderwaterState(),hello(),1000))
        for(p in listOf(changed(hello(),"protocol" to "f1-operator-v1"),changed(hello(),"device" to "F103"),
            changed(hello(),"client_id" to ""),changed(hello(),"session_id" to 7),
            changed(hello(),"capabilities" to JsonArray(emptyList())),
            changed(hello(),"drive_output" to packet("min" to 0,"max" to "100","step" to 1,"firmware_protocol_version" to 6))))
            assertNull(UnderwaterReducer.hello(UnderwaterState(),p,1000))
    }
    @Test fun boardAgeIncludesTimeQueuedBeforeUiAndStrict250msBoundary() {
        val s=ready().copy(report=changed(report(),"report_age_sec" to .125))
        assertTrue(s.copy(now=1124).canDrive)
        assertFalse(s.copy(now=1125).canDrive)
        val queued=UnderwaterReducer.telemetry(ready(),telemetry(changed(report(),"report_age_sec" to .12),2),1100,1230)
        assertFalse(queued.canDrive)
        assertFalse(s.copy(now=999).canDrive)
    }
    @Test fun invalidReportedAgesFlagsAndIdentityFailClosed() {
        val s=ready()
        for(value in listOf<Any?>(null,-.1,"0",true,250))
            assertFalse(s.copy(report=changed(report(),"report_age_sec" to value)).canDrive)
        for((key,value) in listOf("connected" to false,"control_fresh" to "true","available" to false,
            "armed" to false,"result" to "WAIT_NEUTRAL","motion_enabled" to false,"state" to "WAITING",
            "control_generation" to 0,"control_generation" to "7","epoch" to 0,"connection_id" to "",
            "firmware_protocol_version" to 3,"owner" to "another-client"))
            assertFalse("$key=$value",s.copy(report=changed(report(),key to value)).canDrive)
        assertFalse(s.copy(report=JsonObject(report()-"owner")).canDrive)
    }
    @Test fun identityChangeRcAndRecoveryNeverCarryAHold() {
        val s=ready().copy(direction="FORWARD")
        for((key,value) in listOf("connection_id" to "serial2","control_generation" to 8,"epoch" to 5,"source" to "RC")) {
            val next=UnderwaterReducer.telemetry(s,telemetry(changed(report(),key to value),2),1050,1050)
            assertEquals("STOP",next.direction)
            val recovered=UnderwaterReducer.telemetry(next,telemetry(report(),3),1100,1100)
            assertEquals("STOP",recovered.direction)
        }
    }
    @Test fun freshTelemetryCannotRenewAHoldAfterGap() {
        val s=ready().copy(direction="FORWARD")
        val next=UnderwaterReducer.telemetry(s,telemetry(seq=2),1300,1300)
        assertTrue(next.canDrive)
        assertEquals("STOP",next.direction)
    }
    @Test fun staleSessionsSequencesAndTimestampsNeverRenewFreshness() {
        val s=ready()
        for(p in listOf(telemetry(seq=1),telemetry(seq=0),telemetry(seq="2"),telemetry(seq=2.0),
            changed(telemetry(seq=2),"session_id" to "retired"),changed(telemetry(seq=2),"state" to 1)))
            assertSame(s,UnderwaterReducer.telemetry(s,p,1100,1100))
        assertSame(s,UnderwaterReducer.telemetry(s,telemetry(seq=2),999,1100))
        assertSame(s,UnderwaterReducer.telemetry(s,telemetry(seq=2),1200,1100))
    }
    @Test fun foregroundAndVisibilityAreLocalGatesOnly() {
        val s=ready()
        assertTrue(s.canDrive)
        assertFalse(s.copy(foreground=false).canDrive)
        assertFalse(s.copy(visible=false).canDrive)
        assertFalse(UnderwaterReducer.disconnected(s,"lost").canDrive)
        assertEquals("STOP",UnderwaterReducer.tick(s.copy(direction="LEFT"),1250).direction)
    }
    @Test fun encodingSeparatesUnderwaterEnvelopeAndNormalizesNeutral() {
        for(speed in 0..100) {
            val p=UnderwaterIntent("LEFT",speed,7).encode(speed.toLong())
            assertEquals(UNDERWATER_PROTOCOL,p.str("protocol"));assertEquals(UNDERWATER_DEVICE,p.str("device"))
            assertEquals(if(speed==0) "STOP" else "LEFT",p.str("direction"))
            assertEquals(speed.toLong(),p.long("speed_percent"));assertEquals(7,p.long("control_generation").toInt())
            assertFalse(p.containsKey("brush_enabled"))
        }
        assertEquals(0L,UnderwaterIntent("STOP",100,7).encode(1).long("speed_percent"))
    }
    @Test fun defaultUrlPreservesSchemeAndHostOnlyIncludingIpv6() {
        assertEquals("ws://192.168.1.188:8766",underwaterUrl("ws://192.168.1.188:8765/extra?q=1"))
        assertEquals("wss://example.com:8766",underwaterUrl("wss://user:pass@example.com:8000/path"))
        assertEquals("ws://[::1]:8766",underwaterUrl("ws://[::1]:8765/path"))
        assertThrows(IllegalArgumentException::class.java) {underwaterUrl("http://example.com")}
    }
}
