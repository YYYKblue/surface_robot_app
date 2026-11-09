package cn.surface.operator.core

import kotlinx.serialization.json.*
import kotlin.math.ceil

/** Transport metadata is separate from robot state; display streams never renew control freshness. */
data class StreamState(
    val gatewaySession:String="",
    val retiredGatewaySessions:List<String> = emptyList(),
    val sequence:Long?=null,
    val lastTelemetryReceivedAt:Long=-1,
    val tracks:Map<String,JsonObject> = emptyMap(),
    val retiredRuntimeSessions:List<String> = emptyList(),
    val runtimeChangedAt:Long=-1,
    val mapReceivedAt:Long=-1,
    val mapRevision:Long=-1
)

internal fun retire(sessions:List<String>,value:String,limit:Int):List<String> =
    if(value.isEmpty() || value in sessions) sessions else (sessions+value).takeLast(limit)

internal object StreamReducer {
    private fun acceptSession(s:OperatorState,session:String):OperatorState? {
        if(session.isEmpty() || session==s.stream.gatewaySession) return s
        if(session in s.stream.retiredGatewaySessions) return null
        return s.copy(telemetry=EMPTY,receivedAt=-1,driving=false,manual=s.manual.cleared(),picking=false,candidate=null,
            stream=StreamState(gatewaySession=session,
                retiredGatewaySessions=retire(s.stream.retiredGatewaySessions,s.stream.gatewaySession,8),
                // Map decoding is a separate lane. A map already received on this
                // connection may precede the first telemetry/hello of its session.
                mapReceivedAt=s.stream.mapReceivedAt,mapRevision=s.stream.mapRevision))
    }

    fun hello(s:OperatorState,p:JsonObject):OperatorState {
        val next=acceptSession(s,p.str("session_id")) ?: return s
        return next.copy(connected=true,clientId=p.str("client_id"),
            capabilities=p.arr("capabilities").mapNotNull {(it as? JsonPrimitive)?.contentOrNull}.toSet(),
            map=null,mapSession="",telemetry=EMPTY,receivedAt=-1,picking=false,candidate=null,
            driving=false,manual=next.manual.cleared(),
            stream=next.stream.copy(mapReceivedAt=-1,mapRevision=-1))
    }

    /** Return null for an old/malformed packet without touching any safety gate. */
    fun telemetry(original:OperatorState,p:JsonObject,now:Long,receivedAt:Long):Pair<OperatorState,JsonObject>? {
        if(receivedAt<0 || receivedAt>now) return null
        if("stream" in p && p["stream"] !is JsonObject) return null
        val stream=p.obj("stream")
        val sequence=if("sequence" in stream) {
            val value=stream["sequence"] as? JsonPrimitive ?: return null
            if(value.isString) return null
            value.longOrNull?.takeIf {it>=0} ?: return null
        } else null
        val queue=if("server_queue_ms" in stream) {
            val value=stream["server_queue_ms"] as? JsonPrimitive ?: return null
            if(value.isString) return null
            value.doubleOrNull?.takeIf {it.isFinite() && it>=0} ?: return null
        } else 0.0
        val s=acceptSession(original,stream.str("session")) ?: return null
        if(receivedAt<s.stream.lastTelemetryReceivedAt || sequence!=null && s.stream.sequence!=null && sequence<=s.stream.sequence) return null
        val runtimeSession=p.obj("runtime").str("session_id")
        if(runtimeSession.isNotEmpty() && runtimeSession in s.stream.retiredRuntimeSessions) return null
        // Never compare Nano's monotonic or wall clock with Android's clock.
        // Round queue age up so sub-millisecond age cannot grant extra validity.
        val age=ceil(queue)
        val adjusted=if(age>receivedAt.toDouble()) -1 else receivedAt-age.toLong()
        val tracks=s.stream.tracks.toMutableMap()
        p.obj("tasks").forEach {(kind,entry) ->
            (entry as? JsonObject)?.get("actual_track")?.let {if(it is JsonObject) tracks[kind]=it}
        }
        val next=s.copy(receivedAt=adjusted,stream=s.stream.copy(sequence=sequence ?: s.stream.sequence,
            lastTelemetryReceivedAt=receivedAt,tracks=tracks))
        return next to attachTracks(next,p)
    }

    fun tracks(original:OperatorState,p:JsonObject):OperatorState {
        val s=acceptSession(original,p.str("session_id")) ?: return original
        val tracks=s.stream.tracks.toMutableMap()
        p.obj("tasks").forEach {(kind,entry) ->
            if(kind in setOf("rectangle","l") && entry is JsonObject) tracks[kind]=entry
        }
        val next=s.copy(stream=s.stream.copy(tracks=tracks))
        return next.copy(telemetry=attachTracks(next,next.telemetry))
    }

    fun map(s:OperatorState,p:JsonObject,grid:GridMap?,receivedAt:Long):OperatorState {
        val session=p.str("runtime_session_id").ifEmpty {p.obj("map").str("runtime_session_id")}
            .ifEmpty {p.str("session_id")}
        if(receivedAt<0 || receivedAt<s.stream.mapReceivedAt || session.isNotEmpty() && session in s.stream.retiredRuntimeSessions ||
            session.isNotEmpty() && session!=s.runtime.str("session_id") && receivedAt<s.stream.runtimeChangedAt) return s
        if(grid!=null && session==s.mapSession && grid.revision>0 && s.stream.mapRevision>grid.revision) return s
        val retired=if(session.isNotEmpty() && session!=s.mapSession)
            retire(s.stream.retiredRuntimeSessions,s.mapSession,16) else s.stream.retiredRuntimeSessions
        val next=s.copy(map=grid,mapSession=session,picking=false,candidate=null,stream=s.stream.copy(
            retiredRuntimeSessions=retired,mapReceivedAt=receivedAt,mapRevision=grid?.revision ?: -1))
        return next.copy(telemetry=attachTracks(next,next.telemetry))
    }

    private fun attachTracks(s:OperatorState,p:JsonObject):JsonObject {
        if(p.isEmpty()) return p
        val tasks=JsonObject(p.obj("tasks").mapValues {(kind,entry) ->
            val task=entry as? JsonObject ?: return@mapValues entry
            val track=s.stream.tracks[kind]
            val valid=track!=null && track.str("map_id").isNotEmpty() && track.str("map_id")==task.str("map_id") &&
                track.str("run_id")==task.str("run_id") && track.str("frame_id")== (s.map?.frame ?: "map")
            JsonObject(task.filterKeys {it!="expected_track" && it!="actual_track"}+
                if(valid) mapOf("actual_track" to track) else emptyMap())
        })
        return JsonObject(p.filterKeys {it!="scan"}+mapOf("tasks" to tasks))
    }
}
