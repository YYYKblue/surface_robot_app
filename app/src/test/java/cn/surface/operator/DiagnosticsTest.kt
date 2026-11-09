package cn.surface.operator

import cn.surface.operator.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    private fun history(session:String,vararg events:JsonObject)=packet("type" to "telemetry","control_fresh" to true,
        "diagnostics" to packet("session" to session,"events" to JsonArray(events.toList())))
    private fun event(id:Long,reason:String="input_timeout",time:Any?=1794096000.125)=packet("id" to id,
        "source" to "gateway","time_unix" to time,"reason" to reason,"details" to packet("input_age_ms" to 310,"input_timeout_ms" to 250))
    @Test fun upstreamFixturesReplayDeduplicateReportGapsAndRestart() {
        val raw=javaClass.getResource("/gateway-diagnostics.json")!!.readText()
        val frames=wireJson.parseToJsonElement(raw) as JsonArray
        val logs=DiagnosticRecorder()
        logs.telemetry(frames[0] as JsonObject)
        val initial=logs.snapshot()
        logs.telemetry(frames[1] as JsonObject)
        assertSame(initial,logs.snapshot())
        logs.telemetry(frames[2] as JsonObject)
        assertEquals(2,logs.snapshot().count {it.eventId!=null})
        val ack=logs.snapshot().first {it.reason=="ACK progress timeout"}
        assertTrue(ack.message.contains("下位机 ACK 确认超时"))
        assertEquals(.21,ack.details.num("max_tx_gap_sec"),.00001)
        assertEquals(1794096000125L,ack.timeMillis)
        assertTrue(ack.nanoTime)
        logs.telemetry(frames[3] as JsonObject)
        assertEquals(5L,logs.snapshot().first {"missing_events" in it.details}.details.long("missing_events"))
        logs.telemetry(frames[4] as JsonObject)
        assertEquals(2,logs.snapshot().count {it.eventId==1L})
        assertTrue(logs.snapshot().any {it.session=="fixture-gateway-2"})
    }
    @Test fun reconnectAndClearDoNotReplayOldHistory() {
        val logs=DiagnosticRecorder();val frame=history("session",event(1))
        logs.connection("robot-a");logs.telemetry(frame)
        logs.connection("robot-a");logs.telemetry(frame)
        assertEquals(1,logs.snapshot().count {it.eventId==1L})
        logs.clear();logs.telemetry(frame)
        assertTrue(logs.snapshot().isEmpty())
        logs.telemetry(history("session",event(1),event(2)))
        assertEquals(listOf(2L),logs.snapshot().mapNotNull {it.eventId})
    }
    @Test fun unorderedAndDuplicateIdsAreConsumedOnce() {
        val logs=DiagnosticRecorder()
        logs.telemetry(history("session",event(3),event(1),event(2),event(2)))
        assertEquals(listOf(3L,2L,1L),logs.snapshot().mapNotNull {it.eventId})
        assertFalse(logs.snapshot().any {"missing_events" in it.details})
    }
    @Test fun malformedOptionalEventsDoNotBreakOtherLogs() {
        val logs=DiagnosticRecorder()
        logs.telemetry(history("session",packet("id" to "bad"),event(1,time="NaN"),event(2,time=1e100)),1234)
        val events=logs.snapshot().filter {it.eventId!=null}
        assertEquals(2,events.size)
        assertTrue(events.all {!it.nanoTime && it.timeMillis==1234L})
        logs.telemetry(packet("diagnostics" to "unsupported"))
    }
    @Test fun oldGatewayOnlyLogsBridgeChangesNotEachAgeUpdate() {
        val logs=DiagnosticRecorder()
        fun frame(age:Double)=packet("control_fresh" to true,"state" to packet("state" to "FAULT","reason" to "CTRL timeout","ctrl_age_sec" to age))
        logs.telemetry(frame(.5));logs.telemetry(frame(.6));logs.telemetry(frame(.7))
        assertEquals(1,logs.snapshot().count {it.source==LogSource.BRIDGE})
        assertTrue(logs.snapshot().first {it.source==LogSource.BRIDGE}.message.contains("CTRL 状态超时"))
    }
    @Test fun historyIsBoundedAndExportDoesNotTruncateAt48k() {
        val logs=DiagnosticRecorder()
        repeat(2100) {logs.append(LogSource.APP,"event $it " + "长日志".repeat(30))}
        val entries=logs.snapshot();assertEquals(2000,entries.size)
        assertTrue(entries.last().reason.startsWith("event 100 "))
        val report=diagnosticReport(OperatorState(logs=entries),"test")
        assertTrue(report.length>48000);assertTrue(report.contains("event 2099"));assertTrue(report.contains("event 100 "))
        assertFalse(report.contains("event 99 "))
    }
    @Test fun detailsAreBoundedSecretsRedactedAndUnknownFieldsPreserved() {
        val logs=DiagnosticRecorder()
        logs.append(LogSource.GATEWAY,"future_reason",packet("token" to "secret","nested" to packet("access_token" to "secret"),"new_value" to 42,"long" to "x".repeat(9000)))
        val entry=logs.snapshot().single()
        assertFalse(entry.exportLine().contains("secret"))
        assertEquals(42L,entry.details.long("new_value"));assertTrue(entry.details.str("long").length<=1000)
        assertTrue(entry.matches(LogSource.GATEWAY,"future_reason"));assertFalse(entry.matches(LogSource.APP,""))
    }
    @Test fun staleAndSendDelayTransitionsAreLoggedOnceWithoutChangingControl() {
        val logs=DiagnosticRecorder()
        val s=OperatorState(page=Page.MANUAL,connected=true,clientId="me",receivedAt=10,now=100,driving=true)
        logs.observeApp(s);logs.observeApp(s)
        val stale=StateReducer.tick(s,900);logs.observeApp(stale);logs.observeApp(stale)
        assertEquals(1,logs.snapshot().count {it.reason=="遥测未收到或超过 800 ms"})
        logs.sendTick(210,true);logs.sendTick(250,true);logs.sendTick(50,true)
        assertEquals(1,logs.snapshot().count {it.reason=="手动发送定时器延迟"})
        assertEquals(1,logs.snapshot().count {it.reason=="手动发送定时器恢复"})
        assertTrue(s.driving);assertFalse(stale.driving)
    }
}
