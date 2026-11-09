package cn.surface.operator

import cn.surface.operator.core.*
import cn.surface.operator.mock.DemoGeometry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun trackCases()=(wireJson.parseToJsonElement(
    ExpectedTrackTest::class.java.getResource("/expected-track.json")!!.readText()) as JsonArray).map {it as JsonObject}
internal fun historyState():OperatorState {
    val track=packet("frame_id" to "map","map_id" to "fixture-map","run_id" to "run-1",
        "segments" to wireJson.parseToJsonElement("[[[1,2],[2,3],null,[4,5]],[[5,5],[6,5]]]"))
    return OperatorState(connected=true,receivedAt=100,now=100,taskKind="l",
        map=GridMap(160,100,.05,Point(0.0,0.0),0.0,1,"map",ByteArray(16000) {i ->
            val x=i%160;val y=i/160
            when {x<5 || x>154 || y<5 || y>94 -> -1; x==5 || x==154 || y==5 || y==94 -> 100; else -> 0}
        }),
        telemetry=packet("localization" to packet("map_id" to "fixture-map"),
            "pose" to packet("x" to 2,"y" to 3,"yaw" to .7),
            "scan" to packet("frame_id" to "map","points" to wireJson.parseToJsonElement("[[1,1]]")),
            "tasks" to packet("l" to packet("fresh" to true,"map_id" to "fixture-map","run_id" to "run-1",
                "actual_track" to track,"expected_track" to trackCases().first().obj("expected_track")))))
}

class ExpectedTrackTest {
    @Test fun demoUsesActualUpstreamCenterGeometryRatherThanUntrimmedWalls() {
        // Geometry still drives the offline simulation, but is no longer a displayed overlay.
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
    @Test fun historyPreservesInvalidPointGapsAndDistinctSegments() {
        val s=historyState()
        val lines=actualTrackPolylines(s.task,s.map!!)
        assertEquals(listOf(2,1,2),lines.map {it.size})
        assertEquals(Point(4.0,5.0),lines[1].single())
        // Historical tracks remain viewable after telemetry expires.
        assertEquals(lines,actualTrackPolylines(s.copy(now=900).task,s.map))
    }
    @Test fun historyRequiresMatchingFrameMapAndRun() {
        val s=historyState();val actual=s.task.obj("actual_track")
        for(patch in listOf(packet("frame_id" to "odom"),packet("map_id" to "other"),packet("run_id" to "old"))) {
            val task=JsonObject(s.task+packet("actual_track" to JsonObject(actual+patch)))
            assertTrue(actualTrackPolylines(task,s.map!!).isEmpty())
        }
        assertTrue(actualTrackPolylines(JsonObject(s.task-"actual_track"),s.map!!).isEmpty())
        assertTrue(actualTrackPolylines(JsonObject(s.task+packet("run_id" to "")),s.map).isEmpty())
    }
}
