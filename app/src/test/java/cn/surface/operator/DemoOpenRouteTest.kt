package cn.surface.operator

import android.os.Looper
import cn.surface.operator.core.*
import cn.surface.operator.mock.DemoGateway
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DemoOpenRouteTest {
    @Test fun fiveEdgesReplayLeftAndRightTransfersThenCompleteWithCoverageGap() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val tasks=mutableListOf<JsonObject>()
        val gateway=DemoGateway(scope,{ raw ->
            val p=wireJson.parseToJsonElement(raw) as JsonObject
            if(p.str("type")=="telemetry") tasks.add(p.obj("tasks").obj("l"))
        },{_,_->})
        try {
            gateway.connect()
            gateway.send(packet("op" to "initial_pose","x" to 1,"y" to 1,"yaw" to 0,"request_id" to "pose"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
            gateway.send(packet("op" to "task","task_type" to "l","action" to "start","request_id" to "start"))
            tasks.clear()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(19))
            val phases=tasks.map {it.str("phase")}
            val left=listOf("EDGE_STOP","LEFT_EXIT_WALL","LEFT_SHIFT_TO_ENTRY","TURN_LEFT_TO_NEXT_EDGE","APPROACH_NEXT_EDGE")
            assertTrue(left.all {it in phases})
            assertEquals(left.map {phases.indexOf(it)}.sorted(),left.map {phases.indexOf(it)})
            assertTrue("MOVE_INWARD" in phases);assertTrue("TURN_TO_NEXT_EDGE" in phases)
            assertEquals((0..4).map {"edge_$it"}.toSet(),tasks.map {it.str("current_edge_id")}.toSet())
            val result=tasks.last()
            assertEquals("COMPLETE",result.str("phase"));assertEquals("remaining_open_route",result.str("completion_scope"))
            assertFalse(result.flag("coverage_complete"));assertTrue(result.arr("remaining_contact_intervals").isNotEmpty())
            assertEquals(5,result.obj("expected_track").arr("cleaning_segments").size)
            assertEquals(0L,result.long("route_chain_index"));assertEquals(2L,result.long("route_chain_count"))
            assertFalse(result.flag("output_enabled"))
            assertEquals(.4,result.num("map_front_clearance_m"),1e-9)
            assertEquals(.33,result.num("wall_target_m"),0.0)
            assertEquals(.32,result.num("wall_band_min_m"),0.0)
            assertEquals(.36,result.num("wall_band_max_m"),0.0)
            assertEquals("boat_center_stop",result.obj("expected_track").str("basis"))
            tasks.filter {it.str("phase")=="EDGE_STOP"}.forEach {assertEquals(.4,it.num("map_front_clearance_m"),1e-9)}
        } finally {gateway.close();scope.cancel()}
    }
}
