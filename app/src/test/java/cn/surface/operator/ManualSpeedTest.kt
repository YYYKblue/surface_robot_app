package cn.surface.operator

import cn.surface.operator.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ManualSpeedTest {
    private val output=packet("min" to 0,"max" to 100,"step" to 1,"firmware_protocol_version" to 3,"available" to true)
    private fun ready(stateFields:JsonObject=EMPTY,telemetryFields:JsonObject=EMPTY):OperatorState {
        val state=JsonObject(packet("connected" to true,"state" to "READY","source" to "NANO","armed" to true,
            "result" to "OK","connection_id" to "serial-a","epoch" to 1,"ctrl_age_sec" to .01,"ack_age_sec" to .01,
            "firmware_protocol_version" to 3,"direction" to "STOP","speed_percent" to 25)+stateFields)
        return OperatorState(connected=true,clientId="me",receivedAt=1000,now=1000,page=Page.MANUAL,
            capabilities=setOf(CONTINUOUS_DRIVE_CAPABILITY),telemetry=JsonObject(packet("type" to "telemetry",
                "control_fresh" to true,"drive_output" to output,"state" to state)+telemetryFields))
    }

    @Test fun encodesEveryIntegerWithoutQuantizationAndRejectsOutOfRange() {
        for(speed in 0..100) {
            val wire=ManualIntent("FORWARD",speed).encode(1)
            assertEquals(speed.toLong(),wire.long("speed_percent"))
            assertEquals(if(speed==0) "STOP" else "FORWARD",wire.str("direction"))
        }
        for(speed in listOf(-1,101,Int.MIN_VALUE,Int.MAX_VALUE)) {
            try {ManualIntent(speed=speed).encode(1);fail("accepted $speed")} catch(_:IllegalArgumentException) {}
        }
    }

    @Test fun fixturesCheckedByGatewayAreActualAndroidEncoderOutput() {
        val raw=javaClass.getResource("/outbound-continuous-drive.json")!!.readText()
        val fixtures=wireJson.parseToJsonElement(raw) as JsonArray
        for(element in fixtures) {
            val p=element.jsonObject
            val intent=ManualIntent(p.str("direction"),p.long("speed_percent").toInt(),p.str("mode"),
                p.flag("brush_enabled"),p.long("brush_percent").toInt(),p.flag("thrust_enabled"),p.long("thrust_percent").toInt())
            assertEquals(p,intent.encode(p.long("seq")))
        }
    }

    @Test fun capabilityIsRequiredForNewValuesAndLegacyFallbackIsDeterministic() {
        val current=ready()
        assertTrue((0..100).all(current::acceptsManualSpeed))
        assertFalse(current.acceptsManualSpeed(101))
        val old=current.copy(capabilities=emptySet(),manual=ManualIntent(speed=37))
        assertEquals(LEGACY_DRIVE_SPEEDS,(0..100).filter(old::acceptsManualSpeed))
        assertEquals(25,old.normalizedManualSpeed())
        assertEquals(75,old.normalizedManualSpeed(75))
        assertNull(old.driveOutputBlockReason)
    }

    @Test fun zeroMainOutputDoesNotClearIndependentAuxiliaries() {
        val zero=ManualIntent("FORWARD",0,brush=true,brushPercent=-50,thrust=true,thrustPercent=40)
        assertTrue(zero.active)
        val wire=zero.encode(1)
        assertEquals("STOP",wire.str("direction"))
        assertTrue(wire.flag("brush_enabled"));assertEquals(-50L,wire.long("brush_percent"))
        assertTrue(wire.flag("thrust_enabled"));assertEquals(40L,wire.long("thrust_percent"))
        assertFalse(zero.copy(brushPercent=0,thrustPercent=0).active)
        assertFalse(zero.cleared().active)
    }

    @Test fun descriptorMustBePresentStrictAndAvailableForEveryTelemetry() {
        assertNull(ready().driveOutputBlockReason)
        for(key in listOf("min","max","step","firmware_protocol_version")) {
            for(value in listOf(JsonNull,JsonPrimitive("${output[key]}"),JsonPrimitive(true),JsonPrimitive(0.0))) {
                val bad=JsonObject(output+mapOf(key to value))
                assertNotNull("$key=$value",ready(telemetryFields=packet("drive_output" to bad)).driveOutputBlockReason)
            }
        }
        for(value in listOf(JsonNull,JsonPrimitive("true"),JsonPrimitive(1),JsonPrimitive(false))) {
            val bad=JsonObject(output+mapOf("available" to value))
            assertNotNull(ready(telemetryFields=packet("drive_output" to bad)).driveOutputBlockReason)
        }
        assertNotNull(ready(telemetryFields=packet("drive_output" to EMPTY)).driveOutputBlockReason)
        assertNotNull(ready(telemetryFields=packet("drive_output" to JsonNull)).driveOutputBlockReason)
        assertNotNull(ready(telemetryFields=packet("drive_output" to JsonObject(output+packet("firmware_protocol_version" to 2)))).driveOutputBlockReason)
    }

    @Test fun freshTelemetryDoesNotBypassBridgeReadyAndAcknowledgementGates() {
        for(fields in listOf(packet("connected" to false),packet("armed" to false),packet("armed" to "true"),
            packet("source" to "RC"),packet("state" to "HANDSHAKE"),packet("result" to "ERROR"),
            packet("ctrl_age_sec" to .4),packet("ack_age_sec" to .4),packet("ack_age_sec" to -1),
            packet("ack_age_sec" to "0"),packet("ctrl_age_sec" to true)))
            assertNotNull("fields=$fields",ready(stateFields=fields).driveOutputBlockReason)
        for(value in listOf(JsonPrimitive(false),JsonPrimitive("true"),JsonNull))
            assertNotNull(ready(telemetryFields=packet("control_fresh" to value)).driveOutputBlockReason)
        assertNotNull(ready().copy(now=1800).driveOutputBlockReason)
        assertNull(ready(stateFields=packet("state" to "ACTIVE")).driveOutputBlockReason)
    }

    @Test fun ctrlAndAckAgeIncludeGatewayAndAppWaitingAtExactBoundary() {
        val s=ready(stateFields=packet("ctrl_age_sec" to .2,"ack_age_sec" to .2),
            telemetryFields=packet("stream" to packet("control_age_ms" to 100)))
        assertNull(s.copy(now=1099).driveOutputBlockReason)
        assertNotNull(s.copy(now=1100).driveOutputBlockReason)
        assertEquals(.4,s.copy(now=1100).reportedControlAgeSec("ctrl_age_sec")!!,1e-9)
        for(value in listOf(JsonNull,JsonPrimitive(-1),JsonPrimitive("0"),JsonPrimitive(true)))
            assertNotNull(ready(telemetryFields=packet("stream" to packet("control_age_ms" to value))).driveOutputBlockReason)
        assertNull(s.copy(receivedAt=-1).reportedControlAgeSec("ctrl_age_sec"))
        assertNull(s.copy(now=999).reportedControlAgeSec("ctrl_age_sec"))
    }

    @Test fun controlIdentityDistinguishesSerialSourceEpochAndMissingFields() {
        val s=ready()
        assertEquals(s.manualControlIdentity,ready().manualControlIdentity)
        for(fields in listOf(packet("connection_id" to "serial-b"),packet("source" to "RC"),packet("epoch" to 2),
            packet("epoch" to "1"),packet("connection_id" to JsonNull)))
            assertNotEquals(s.manualControlIdentity,ready(stateFields=fields).manualControlIdentity)
        assertNull(s.copy(telemetry=EMPTY).manualControlIdentity)
        assertNull(s.copy(capabilities=emptySet()).manualControlIdentity)
        assertNotEquals(s.manualControlIdentity,s.copy(telemetry=packet("state" to EMPTY)).manualControlIdentity)
    }

    @Test fun feedbackSeparatesRequestedOutputRcScaleAndStaleReportedState() {
        val s=ready(stateFields=packet("source" to "RC","speed_percent" to 3,"direction" to "LEFT"))
            .copy(manual=ManualIntent("FORWARD",37))
        assertTrue(s.driveFeedback.contains("App 请求：FORWARD · 37%"))
        assertTrue(s.driveFeedback.contains("F103 回报：RC · LEFT · 遥控器 3 档（独立标度）"))
        assertTrue(ready().driveFeedback.contains("F103 回报：NANO · STOP · 25%"))
        assertTrue(s.copy(now=1400).driveFeedback.contains("F103 回报：状态未收到或已过期"))
        assertFalse(s.copy(now=1400).driveFeedback.contains("遥控器 3 档"))
    }
}
