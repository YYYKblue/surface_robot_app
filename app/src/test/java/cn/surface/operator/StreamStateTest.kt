package cn.surface.operator

import cn.surface.operator.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StreamStateTest {
    private fun state()=OperatorState(connected=true,clientId="me",page=Page.MANUAL,
        capabilities=setOf("task_selection","initial_pose","start_conditions"))
    private fun track(run:String="r",frame:String="map")=packet("map_id" to "map-a","run_id" to run,
        "frame_id" to frame,"segments" to wireJson.parseToJsonElement("[[[0,0],[1,0]],[[2,0],[3,0]]]"))
    private fun telemetry(sequence:Long=1,session:String="g",runtime:String="a",run:String="r",queue:Any=0)=packet(
        "type" to "telemetry","stream" to packet("session" to session,"sequence" to sequence,"server_queue_ms" to queue),
        "active_client" to "me","can_set_initial_pose" to true,
        "runtime" to packet("session_id" to runtime,"map_available" to true),
        "localization" to packet("map_id" to "map-a"),
        "tasks" to packet("rectangle" to packet("map_id" to "map-a","run_id" to run,"fresh" to true,"can_start" to true)))
    private fun grid(revision:Long=1)=GridMap(2,2,.05,Point(0.0,0.0),0.0,revision,"map",ByteArray(4))
    private fun map(session:String)=packet("type" to "map","runtime_session_id" to session)

    @Test fun receiptAndServerQueueAgeCannotReviveManualOutput() {
        var s=StateReducer.telemetry(state(),telemetry(),1000)
        s=s.copy(driving=true,manual=ManualIntent("FORWARD"))
        assertTrue(s.canDrive)
        val queued=StateReducer.telemetry(s,telemetry(sequence=2,queue=750.1),1100,1050)
        assertEquals(299L,queued.receivedAt)
        assertFalse(queued.fresh);assertFalse(queued.driving);assertFalse(queued.manual.active)
        val recovered=StateReducer.telemetry(queued,telemetry(sequence=3),1150)
        assertTrue(recovered.fresh);assertFalse(recovered.driving)
        // Nano timestamps have deliberately unrelated epochs and do not enter freshness.
        val p=telemetry(sequence=4)
        val clockIndependent=JsonObject(p+mapOf("stream" to JsonObject(p.obj("stream")+mapOf(
            "generated_monotonic" to JsonPrimitive(987654321),"generated_unix" to JsonPrimitive(-100)))))
        assertEquals(1200L,StateReducer.telemetry(recovered,clockIndependent,1200).receivedAt)
    }

    @Test fun rejectsRegressingSequenceReceiptAndInvalidQueueBeforeUpdatingState() {
        val s=StateReducer.telemetry(state(),telemetry(sequence=10),1000)
        assertSame(s,StateReducer.telemetry(s,telemetry(sequence=9),1100))
        assertSame(s,StateReducer.telemetry(s,telemetry(sequence=10),1100))
        assertSame(s,StateReducer.telemetry(s,telemetry(sequence=11),1100,999))
        assertSame(s,StateReducer.telemetry(s,telemetry(sequence=11),1100,1101))
        for(queue in listOf(-1,JsonPrimitive("NaN"),JsonPrimitive("5"),JsonNull,JsonPrimitive(true)))
            assertSame(s,StateReducer.telemetry(s,telemetry(sequence=11,queue=queue),1100))
        assertTrue(StateReducer.telemetry(s,telemetry(sequence=11),1100).fresh)
        val enormous=StateReducer.telemetry(s,telemetry(sequence=11,queue=1e100),1100)
        assertFalse(enormous.fresh)
    }

    @Test fun independentTracksSurviveHelloAndMatchRunMapFrameWithoutRenewingFreshness() {
        val t=track()
        var s=StateReducer.tracks(state(),packet("type" to "tracks","session_id" to "g","tasks" to packet("rectangle" to t)))
        assertEquals(-1L,s.receivedAt)
        s=StateReducer.hello(s,packet("session_id" to "g","client_id" to "me"))
        s=StateReducer.telemetry(s,telemetry(),1000)
        assertEquals(t,s.task.obj("actual_track"))
        s=StateReducer.telemetry(s,telemetry(sequence=2),1050)
        assertEquals(t,s.task.obj("actual_track"))
        s=StateReducer.telemetry(s,telemetry(sequence=3,run="next-run"),1100)
        assertFalse("actual_track" in s.task)
        val wrongFrame=StateReducer.tracks(s,packet("session_id" to "g","tasks" to packet("rectangle" to track("next-run","odom"))))
        assertFalse("actual_track" in wrongFrame.task)
        assertEquals(1100L,wrongFrame.receivedAt)
        val wrongMap=JsonObject(track("next-run")+mapOf("map_id" to JsonPrimitive("other")))
        assertFalse("actual_track" in StateReducer.tracks(s,packet("session_id" to "g","tasks" to packet("rectangle" to wrongMap))).task)
    }

    @Test fun gatewayChangeRejectsRetiredSessionAndDropsOldTrackCache() {
        var s=StateReducer.telemetry(state(),telemetry(),1000)
        s=StateReducer.tracks(s,packet("session_id" to "g","tasks" to packet("rectangle" to track())))
        s=StateReducer.telemetry(s,telemetry(session="g2"),1100)
        assertTrue(s.stream.tracks.isEmpty());assertFalse("actual_track" in s.task)
        assertSame(s,StateReducer.telemetry(s,telemetry(sequence=100,session="g"),1200))
        assertSame(s,StateReducer.tracks(s,packet("session_id" to "g","tasks" to packet("rectangle" to track()))))
        assertSame(s,StateReducer.hello(s,packet("session_id" to "g")))
    }

    @Test fun legacyEmbeddedTracksRemainCompatibleAndHeavyUnusedOverlaysAreStripped() {
        val t=track()
        val base=telemetry()
        val old=JsonObject(base.filterKeys {it!="stream"}+mapOf("scan" to packet("points" to JsonArray(emptyList())),
            "tasks" to packet("rectangle" to JsonObject(base.obj("tasks").obj("rectangle")+mapOf(
                "actual_track" to t,"expected_track" to packet("large" to true))))))
        val s=StateReducer.telemetry(state(),old,1000)
        assertEquals(t,s.task.obj("actual_track"));assertFalse("scan" in s.telemetry);assertFalse("expected_track" in s.task)
        val next=StateReducer.telemetry(s,JsonObject(base.filterKeys {it!="stream"}),1100)
        assertEquals(t,next.task.obj("actual_track"))
    }

    @Test fun lateMapDecodeAndLateResetCannotRestoreRetiredRuntime() {
        var s=StateReducer.telemetry(state(),telemetry(runtime="a"),1000)
        s=StateReducer.map(s,map("a"),grid(),1010)
        s=StateReducer.telemetry(s,telemetry(sequence=2,runtime="b"),1040)
        assertNull(s.map)
        assertSame(s,StateReducer.map(s,map("a"),grid(2),1030))
        val b=grid(3)
        s=StateReducer.map(s,map("b"),b,1050)
        assertSame(b,s.map)
        val c=grid(1)
        s=StateReducer.map(s,map("c"),c,1070)
        assertFalse(s.mapMatchesRuntime);assertFalse(s.canStart);assertFalse(s.canPose)
        s=StateReducer.telemetry(s,telemetry(sequence=3,runtime="c"),1080)
        assertSame(c,s.map);assertTrue(s.mapMatchesRuntime)
        assertSame(s,StateReducer.map(s,map("b"),null,1090))
        assertNull(StateReducer.map(s,map("c"),null,1091).map)
    }

    @Test fun futureMapBeforeOlderTelemetryIsPreservedAndSameSessionRevisionNeverRegresses() {
        var s=StateReducer.telemetry(state(),telemetry(runtime="a"),1000)
        s=StateReducer.map(s,map("c"),grid(10),1100)
        s=StateReducer.telemetry(s,telemetry(sequence=2,runtime="b"),1110,1050)
        assertNotNull(s.map);assertFalse(s.mapMatchesRuntime)
        assertSame(s,StateReducer.map(s,map("c"),grid(9),1120))
        assertSame(s,StateReducer.map(s,map("unknown-old"),grid(),1040))
        val same=StateReducer.map(s,map("c"),grid(10),1130)
        assertEquals(10L,same.map!!.revision)
    }

    @Test fun firstRuntimeRejectsUnknownOldMapAndAcceptsNestedOrLegacySessionMetadata() {
        val s=StateReducer.telemetry(state(),telemetry(runtime="new"),2000)
        assertSame(s,StateReducer.map(s,map("old-unseen"),grid(),1000))
        val g=grid()
        val nested=packet("map" to packet("runtime_session_id" to "new"))
        assertSame(g,StateReducer.map(s,nested,g,1500).map)
        assertEquals("new",StateReducer.map(s,packet("session_id" to "new"),g,1500).mapSession)
        val legacy=StateReducer.map(s,packet("type" to "map"),g,2100)
        assertSame(g,legacy.map);assertTrue(legacy.mapMatchesRuntime)
        assertNull(StateReducer.map(legacy,packet("map" to JsonNull),null,2200).map)
    }

    @Test fun firstStateAfterGatewayChangeClearsMismatchedMapAndRepeatedHelloCannotResetSequence() {
        var s=StateReducer.telemetry(state(),telemetry(sequence=10),1000)
        s=StateReducer.map(s,map("a"),grid(),1010)
        s=StateReducer.telemetry(s,telemetry(session="g2",runtime="b"),1100)
        assertNull(s.map)
        val hello=StateReducer.hello(s,packet("session_id" to "g2","client_id" to "me"))
        assertSame(hello,StateReducer.telemetry(hello,telemetry(session="g2",runtime="b"),1200))
        assertTrue(StateReducer.telemetry(hello,telemetry(sequence=2,session="g2",runtime="b"),1200).fresh)
        val early=StateReducer.map(state(),map("unseen-old"),grid(),1000)
        assertNull(StateReducer.telemetry(early,telemetry(runtime="new"),2000).map)
    }
}
