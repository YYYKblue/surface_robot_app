package cn.surface.operator.connection

import android.os.SystemClock
import cn.surface.operator.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

/** UI-owned H7 session. It has no dependency on surface task or transport state. */
class UnderwaterController(
    parentScope:CoroutineScope,
    private val log:(String,JsonObject)->Unit={_,_->},
    private val clock:()->Long=SystemClock::elapsedRealtime
):AutoCloseable {
    private val job=SupervisorJob(parentScope.coroutineContext[Job])
    private val scope=CoroutineScope(parentScope.coroutineContext+job)
    private val mutable=MutableStateFlow(UnderwaterState())
    val state=mutable.asStateFlow()
    @Volatile private var epoch=0
    private var wanted=false
    private var closed=false
    private var address=""
    private var connection:GatewayConnection?=null
    private var retry:Job?=null
    private var handshakeTimeout:Job?=null
    private val sender=UnderwaterSender(clock,{value,reason -> scope.launch {senderExpired(value,reason)}})
    private data class Incoming(val epoch:Int,val packet:JsonObject,val receivedAt:Long,val revokeHeld:Boolean=false)
    private val inboxLock=Any()
    private val hellos=ArrayDeque<Incoming>()
    private var latest:Incoming?=null
    private val signal=Channel<Unit>(Channel.CONFLATED)
    private var loggedIdentity:List<Any?>?=null

    init {
        scope.launch {
            for(ignored in signal) {
                val packets=synchronized(inboxLock) {
                    val values=hellos.toList()+listOfNotNull(latest)
                    hellos.clear();latest=null;values
                }
                for(p in packets) if(p.epoch==epoch) receive(p)
            }
        }
        scope.launch {
            while(isActive) {
                delay(50)
                applyExpiry()
                val before=mutable.value
                val next=UnderwaterReducer.tick(before,clock())
                mutable.value=next
                if(before.direction!="STOP" && next.direction=="STOP" || sender.hasPending && !next.canDrive)
                    revoke("under_control_unavailable")
                refresh()
            }
        }
    }
    private fun record(message:String,reason:String=message,details:JsonObject=EMPTY) {
        log(message,packet("device" to "underwater","address" to address,"reason" to reason,"state" to details))
    }
    fun connect(address:String,demo:Boolean=false) {
        if(closed) return
        stop("switch_connection");wanted=false;retry?.cancel();handshakeTimeout?.cancel();connection?.close()
        connection=null;epoch++;sender.bind(epoch,null);clearInbox()
        this.address=address.trim()
        val old=mutable.value
        mutable.value=UnderwaterState(demo=demo,foreground=old.foreground,visible=old.visible,connection="水下连接中…")
        if(!demo && runCatching {underwaterUrl(this.address)}.isFailure) {
            mutable.value=UnderwaterReducer.disconnected(mutable.value,"水下地址无效")
            record("水下地址必须是有效的 ws:// 或 wss:// 地址");return
        }
        wanted=true;open()
    }
    private fun open() {
        if(closed || !wanted || !mutable.value.foreground) return
        connection?.close();handshakeTimeout?.cancel()
        val current=++epoch
        sender.bind(current,null);clearInbox()
        mutable.value=UnderwaterReducer.disconnected(mutable.value,"水下连接中…")
        val receive:(String)->Unit={raw->offer(current,raw,clock())}
        // Always enqueue status callbacks: a writer may hold transport and sender locks.
        val status:(Boolean,String)->Unit={ok,message->scope.launch {
            if(current==epoch) {
                if(ok) {
                    if(!mutable.value.connected) mutable.value=mutable.value.copy(connection=message)
                    record(message)
                } else lost(message)
            }
        }}
        try {
            connection=if(mutable.value.demo) UnderwaterDemoGateway(scope,receive,status) else UnderwaterGateway(address,receive,status)
            sender.bind(current,connection)
            connection?.connect()
            handshakeTimeout=scope.launch {
                delay(5000)
                if(current==epoch && !mutable.value.connected) lost("水下协议握手超时")
            }
        } catch(e:Exception) {lost("水下连接失败：${e.message}")}
    }
    private fun clearInbox()=synchronized(inboxLock) {hellos.clear();latest=null}
    private fun offer(current:Int,raw:String,receivedAt:Long) {
        if(current!=epoch) return
        val p=runCatching {wireJson.parseToJsonElement(raw) as? JsonObject}.getOrNull()
        if(p==null || !UnderwaterReducer.validEnvelope(p) || p.str("type") !in setOf("hello","telemetry")) {
            scope.launch {if(current==epoch) lost("水下协议或设备标识不匹配")};return
        }
        var overflow=false
        synchronized(inboxLock) {
            if(current!=epoch) return
            val item=Incoming(current,p,receivedAt)
            if(p.str("type")=="hello") {
                if(hellos.size>=4) overflow=true else hellos.addLast(item)
            } else {
                val applied=mutable.value
                if(applied.connected && p.underText("session_id")!=applied.sessionId) return
                val candidate=UnderwaterReducer.telemetry(applied,p,receivedAt,receivedAt)
                // Coalescing cannot erase a brief loss of permission/identity. The
                // UI must clear the hold even when a newer READY frame follows it.
                val revoke=candidate!==applied && (!candidate.canDrive ||
                    applied.identity!=null && applied.identity!=candidate.identity)
                val prior=latest
                if(prior==null || prior.packet.underLong("sequence")?.let {it<(p.underLong("sequence") ?: -1)}!=false)
                    latest=item.copy(revokeHeld=revoke || prior?.revokeHeld==true)
                else if(revoke) latest=prior.copy(revokeHeld=true)
            }
        }
        if(overflow) scope.launch {if(current==epoch) lost("水下握手接收积压")}
        else signal.trySend(Unit)
    }
    private fun receive(incoming:Incoming) {
        applyExpiry()
        val before=mutable.value
        val p=incoming.packet
        if(p.str("type")=="hello") {
            // One hello per socket; a replacement session must establish a new socket.
            if(before.connected) {lost("水下服务重复握手");return}
            val next=UnderwaterReducer.hello(before,p,clock())
            if(next==null || !sender.activate(epoch)) {lost("水下能力或 H7 V6 协议不匹配");return}
            mutable.value=next;handshakeTimeout?.cancel();loggedIdentity=null
            record("水下服务握手完成")
            return
        }
        var next=UnderwaterReducer.telemetry(before,p,incoming.receivedAt,clock())
        if(next===before) return
        if(incoming.revokeHeld) next=next.copy(direction="STOP")
        mutable.value=next
        if(before.direction!="STOP" && next.direction=="STOP" || sender.hasPending &&
            (!next.canDrive || incoming.revokeHeld || before.identity!=next.identity)) revoke("under_control_changed")
        val key=listOf(next.identity,next.report.str("state"),next.report.str("reason"),next.report.str("last_stop_reason"))
        if(key!=loggedIdentity) {loggedIdentity=key;record("水下控制状态","under_state",next.report)}
        refresh()
    }
    private fun applyExpiry() {
        sender.checkExpiry(epoch)
        if(sender.isExpired) senderExpired(epoch,sender.stopReason)
    }
    private fun senderExpired(current:Int,reason:String) {
        if(current!=epoch || !sender.isExpired || mutable.value.direction=="STOP") return
        mutable.value=mutable.value.copy(direction="STOP")
        record("水下旧操作已清除；请重新按方向键",reason)
    }
    private fun refresh() {
        val s=mutable.value
        if(s.direction=="STOP") return
        val identity=s.identity
        val deadline=s.feedbackDeadline
        if(!s.canDrive || identity==null || deadline==null) {revoke("under_control_unavailable");return}
        if(!sender.update(epoch,UnderwaterIntent(s.direction,s.speed,identity.generation),deadline)) {
            if(sender.isExpired) senderExpired(epoch,sender.stopReason) else revoke("under_send_not_ready")
        }
    }
    fun direction(value:String) {
        if(value !in UNDERWATER_DIRECTIONS || closed) return
        if(value=="STOP") {release();return}
        applyExpiry()
        mutable.value=UnderwaterReducer.tick(mutable.value,clock())
        val s=mutable.value
        if(!s.canDrive || s.speed==0) {revoke("under_control_unavailable");return}
        if(s.direction=="STOP" && !sender.arm(epoch)) return
        mutable.value=s.copy(direction=value)
        refresh()
    }
    fun speed(value:Int) {
        if(value !in 0..100 || closed) return
        applyExpiry()
        mutable.value=UnderwaterReducer.tick(mutable.value,clock()).copy(speed=value)
        if(value==0) release() else refresh()
    }
    private fun release() {
        applyExpiry()
        val s=UnderwaterReducer.tick(mutable.value,clock())
        mutable.value=s.copy(direction="STOP")
        val identity=s.identity
        if(!s.canDrive || identity==null) {revoke("under_control_unavailable");return}
        if(s.direction=="STOP" && !sender.isArmed) return
        if(!sender.release(epoch,identity.generation)) {
            if(sender.isExpired) senderExpired(epoch,sender.stopReason) else revoke("under_send_not_ready")
        }
    }
    /** Local revocation always precedes a socket send and its failure callback. */
    private fun revoke(reason:String,force:Boolean=false) {
        val s=mutable.value
        mutable.value=s.copy(direction="STOP")
        val had=sender.clear(reason,sendStop=false) || s.direction!="STOP"
        if(s.connected && (had || force)) {
            connection?.send(underwaterPacket("op" to "manual_stop","reason" to reason.take(80).ifEmpty {"operator"}))
            record("水下停止手动输出",reason)
        }
    }
    fun stop(reason:String="operator") {
        // Disabling a held button at 0% disposes its gesture after release() has
        // already queued neutral. That UI cleanup must not replace neutral with
        // a generation-changing stop. Actual lifecycle/identity loss uses revoke.
        if(reason in setOf("under_control_changed","under_touch_cancelled") &&
            mutable.value.direction=="STOP" && !sender.isArmed) return
        // A touch cancellation may follow navigation/foreground revocation in the
        // same UI turn. Send only once for that hold; an observing idle client
        // must not repeatedly reset the board's control generation.
        revoke(reason,force=reason in setOf("operator","operator_both","under_button"))
    }
    fun foreground(value:Boolean) {
        val wasForeground=mutable.value.foreground
        if(!value) stop("app_not_foreground")
        mutable.value=mutable.value.copy(foreground=value)
        if(value && !wasForeground && wanted && !mutable.value.connected) {retry?.cancel();open()}
    }
    fun visible(value:Boolean) {
        if(!value && mutable.value.visible) stop("under_panel_hidden")
        mutable.value=mutable.value.copy(visible=value)
    }
    private fun lost(reason:String) {
        connection?.close();connection=null;handshakeTimeout?.cancel()
        epoch++;sender.bind(epoch,null);clearInbox()
        mutable.value=UnderwaterReducer.disconnected(mutable.value,reason)
        record("$reason；旧指令不会重发")
        retry?.cancel()
        if(wanted && mutable.value.foreground && !closed) retry=scope.launch {delay(1500);open()}
    }
    fun disconnect() {
        wanted=false;retry?.cancel();handshakeTimeout?.cancel();stop("disconnect")
        connection?.close();connection=null;epoch++;sender.bind(epoch,null);clearInbox()
        mutable.value=UnderwaterReducer.disconnected(mutable.value,"水下已断开")
    }
    override fun close() {
        if(closed) return
        disconnect();closed=true;sender.close();signal.close();job.cancel()
    }
}
