package cn.surface.operator

import cn.surface.operator.core.*
import cn.surface.operator.mock.DemoGeometry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

internal fun trackCases()=(wireJson.parseToJsonElement(
    ExpectedTrackTest::class.java.getResource("/expected-track.json")!!.readText()) as JsonArray).map {it as JsonObject}
internal fun trackState(track:JsonObject):OperatorState=OperatorState(connected=true,receivedAt=100,now=100,taskKind="l",
    map=GridMap(160,100,.05,Point(0.0,0.0),0.0,1,"map",ByteArray(16000)),
    telemetry=packet("localization" to packet("map_id" to "fixture-map"),
        "tasks" to packet("l" to packet("fresh" to true,"map_id" to "fixture-map","expected_track" to track))))

class ExpectedTrackTest {
    @Test fun upstreamEndpointsStayAtCenterStopIncludingRotatedAndSuffixRoutes() {
        for(case in trackCases()) {
            val track=case.obj("expected_track");val state=trackState(track)
            val overlay=state.centerTrackOverlay()!!
            val segments=track.arr("cleaning_segments")
            assertEquals(segments.size,overlay.stops.size)
            for((i,raw) in segments.withIndex()) {
                val segment=raw as JsonObject
                val wall=case.arr("walls").map {it as JsonObject}.first {it.str("edge_id")==segment.str("edge_id")}
                val a=wall["start"]!!.point()!!;val b=wall["end"]!!.point()!!
                val length=hypot(b.x-a.x,b.y-a.y);val tx=(b.x-a.x)/length;val ty=(b.y-a.y)/length
                val p=overlay.stops[i]
                assertEquals(segment.arr("points").last().point(),p) // Never offset received coordinates again.
                assertEquals(overlay.stop,length-((p.x-a.x)*tx+(p.y-a.y)*ty),1e-9)
                assertEquals(overlay.target,(p.x-a.x)*ty-(p.y-a.y)*tx,1e-9)
                val rotated=GridMap(160,100,.05,Point(2.0,1.0),.7,1,"map",ByteArray(16000))
                val tr=MapTransform(rotated,1200.0,800.0,2.0,Point(20.0,-15.0))
                val roundTrip=tr.screenToWorld(tr.worldToScreen(p))!!
                assertEquals(p.x,roundTrip.x,1e-9);assertEquals(p.y,roundTrip.y,1e-9)
            }
            if(case.str("variant")=="default") {
                assertEquals(.33,overlay.target,0.0);assertTrue(overlay.legend.contains("32–36 cm"));assertTrue(overlay.legend.contains("40 cm"))
            } else {
                assertTrue(overlay.legend.contains("55 cm"))
                assertEquals("edge_2",(segments.first() as JsonObject).str("edge_id"))
            }
        }
    }
    @Test fun demoUsesActualUpstreamCenterGeometryRatherThanUntrimmedWalls() {
        for(case in trackCases().filter {it.str("variant")=="default"}) {
            val expected=case.obj("expected_track").arr("cleaning_segments")
            val edges=DemoGeometry.edges(case.str("kind"))
            assertEquals(expected.size,edges.size)
            for((i,edge) in edges.withIndex()) {
                val points=(expected[i] as JsonObject).arr("points").map {it.point()!!}
                for((actual,reference) in listOf(edge.start to points.first(),edge.stop to points.last())) {
                    assertEquals(reference.x,actual.x,1e-9);assertEquals(reference.y,actual.y,1e-9)
                }
                assertEquals(.4,edge.remaining(edge.stop),1e-9)
            }
        }
    }
    @Test fun legacyStaleAndMismatchedMapsCannotClaimNewStopMarkers() {
        val track=trackCases().first().obj("expected_track");val s=trackState(track)
        assertNull(s.copy(now=900).centerTrackOverlay())
        assertNull(s.copy(map=null).centerTrackOverlay())
        assertNull(s.copy(telemetry=JsonObject(s.telemetry+packet("localization" to packet("map_id" to "other")))).centerTrackOverlay())
        assertNull(trackState(JsonObject(track-"basis")).centerTrackOverlay())
        for(patch in listOf(packet("basis" to "brush_contact"),packet("frame_id" to "odom"),packet("map_id" to "other")))
            assertNull(trackState(JsonObject(track+patch)).centerTrackOverlay())
        val staleTask=JsonObject(s.task+packet("fresh" to false))
        assertNull(s.copy(telemetry=JsonObject(s.telemetry+packet("tasks" to packet("l" to staleTask)))).centerTrackOverlay())
    }
    @Test fun malformedMetadataAndBrokenSegmentsDoNotProduceMisleadingMarkers() {
        val track=trackCases().first().obj("expected_track")
        for(value in listOf(JsonNull,JsonPrimitive(-.4),JsonPrimitive(0),JsonPrimitive("0.4"),JsonPrimitive("NaN")))
            assertNull(trackState(JsonObject(track+packet("front_stop_m" to value))).centerTrackOverlay())
        assertNull(trackState(JsonObject(track+packet("wall_band_min_m" to .5))).centerTrackOverlay())
        val broken=wireJson.parseToJsonElement("[{\"points\":[[1,2],null]},{\"points\":[[1,2]]},{\"points\":[[0,0],[2,3]]}]")
        val overlay=trackState(JsonObject(track+packet("cleaning_segments" to broken))).centerTrackOverlay()!!
        assertEquals(listOf(Point(2.0,3.0)),overlay.stops)
    }
}
