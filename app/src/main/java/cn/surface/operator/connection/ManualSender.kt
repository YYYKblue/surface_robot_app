package cn.surface.operator.connection

import cn.surface.operator.core.ManualIntent
import cn.surface.operator.core.packet

/**
 * A dedicated writer clock, independent of Main and the map decoder's dispatcher.
 * GUI updates renew intent for 250 ms; they never determine the 50 ms wire cadence.
 * All state transitions and actual send calls share one lock, so clear/bind cannot
 * return while an extracted command is still waiting to be sent on an old session.
 */
class ManualSender(
    private val clock:()->Long,
    private val onStopped:(epoch:Int,reason:String)->Unit,
    autoStart:Boolean=true
):AutoCloseable {
    companion object {
        const val PERIOD_MS=50L
        const val INTENT_TTL_MS=250L
        const val TELEMETRY_TTL_MS=800L
    }
    private data class Stopped(val epoch:Int,val reason:String)
    private val lock=Object()
    private var currentEpoch=-1
    private var connection:GatewayConnection?=null
    private var activated=false
    private var isArmed=false
    private var isExpired=false
    private var reason=""
    private var intent:ManualIntent?=null
    private var updatedAt=-1L
    private var telemetryAt=-1L
    private var nextAt=0L
    private var lastSentActive=false
    private var revision=0L
    private var sequence=0L
    private var closed=false
    private val worker:Thread?=if(autoStart) Thread(::runWorker,"operator-manual-sender").apply {
        isDaemon=true
        start()
    } else null

    val epoch:Int get()=synchronized(lock) {currentEpoch}
    val expired:Boolean get()=synchronized(lock) {isExpired}
    val armed:Boolean get()=synchronized(lock) {isArmed}
    val stopReason:String get()=synchronized(lock) {reason}

    /** Caller owns connection lifetime. Binding never carries intent into a new link. */
    fun bind(epoch:Int,connection:GatewayConnection?) = synchronized(lock) {
        if(closed || epoch<currentEpoch) return@synchronized
        currentEpoch=epoch
        this.connection=connection
        activated=false
        cancelLocked()
        isExpired=false;reason=""
        lock.notifyAll()
    }

    fun reset(epoch:Int)=bind(epoch,null)

    /** Only call after the GUI has accepted this epoch's protocol hello. */
    fun activate(epoch:Int):Boolean=synchronized(lock) {
        if(closed || epoch!=currentEpoch || connection==null) return@synchronized false
        if(!activated) {cancelLocked();activated=true}
        true
    }

    /** Explicit user authorization after the GUI has cleared all previous controls. */
    fun arm(epoch:Int):Boolean=synchronized(lock) {
        if(closed || epoch!=currentEpoch || !activated || connection==null) return@synchronized false
        cancelLocked()
        isExpired=false;reason="";isArmed=true
        lock.notifyAll()
        true
    }

    fun update(epoch:Int,value:ManualIntent,telemetryReceivedAt:Long):Boolean {
        var stopped:Stopped?=null
        val accepted=synchronized(lock) {
            if(closed || epoch!=currentEpoch || !activated || !isArmed || isExpired || connection==null)
                return@synchronized false
            val now=clock()
            // Inspect the OLD lease first. A GUI that resumes late cannot renew it.
            val expiredReason=expiryLocked(now)
            if(expiredReason!=null) {
                stopped=stopLocked(expiredReason,true)
                return@synchronized false
            }
            if(telemetryReceivedAt<0 || telemetryReceivedAt>now || now-telemetryReceivedAt>=TELEMETRY_TTL_MS) {
                stopped=stopLocked("telemetry_expired",true)
                return@synchronized false
            }
            if(value!=intent) {revision++;nextAt=now}
            intent=value;updatedAt=now;telemetryAt=telemetryReceivedAt
            lock.notifyAll()
            true
        }
        notifyStopped(stopped)
        return accepted
    }

    /** Returns whether a manual authorization/intent was cleared; keeps expiry latched. */
    fun clear(reason:String,sendStop:Boolean=true):Boolean=synchronized(lock) {
        val hadManual=isArmed || intent!=null || lastSentActive
        val target=connection
        cancelLocked()
        if(sendStop && hadManual && !closed && activated)
            runCatching {target?.send(packet("op" to "manual_stop","reason" to reason))}
        lock.notifyAll()
        hadManual
    }

    private fun cancelLocked() {
        revision++
        isArmed=false;intent=null;updatedAt=-1;telemetryAt=-1
        nextAt=0;lastSentActive=false
    }

    private fun expiryLocked(now:Long):String? {
        if(intent==null) return null
        if(now<updatedAt || now-updatedAt>=INTENT_TTL_MS) return "gui_input_expired"
        if(telemetryAt<0 || now<telemetryAt || now-telemetryAt>=TELEMETRY_TTL_MS) return "telemetry_expired"
        return null
    }

    private fun stopLocked(reason:String,sendStop:Boolean):Stopped {
        val event=Stopped(currentEpoch,reason)
        val target=connection
        cancelLocked()
        isExpired=true;this.reason=reason
        if(sendStop) runCatching {target?.send(packet("op" to "manual_stop","reason" to reason))}
        lock.notifyAll()
        return event
    }

    private fun stepLocked():Stopped? {
        if(closed || !activated || !isArmed || isExpired) return null
        val now=clock()
        expiryLocked(now)?.let {return stopLocked(it,true)}
        val value=intent ?: return null
        if(now<nextAt || !value.active && !lastSentActive) return null
        val target=connection ?: return stopLocked("send_not_ready",false)
        val sentRevision=revision
        // Increment only here, never at GUI refresh or while skipping missed ticks.
        val sent=runCatching {target.send(value.encode(++sequence))}.getOrDefault(false)
        // A connection callback may synchronously clear/bind. Do not alter its new state.
        if(revision!=sentRevision || connection!==target) return null
        if(!sent) return stopLocked("send_not_ready",true)
        lastSentActive=value.active
        nextAt=now+PERIOD_MS
        return null
    }

    /** Deterministic clock step for tests; production uses the dedicated thread. */
    internal fun pump() {
        val stopped=synchronized(lock) {stepLocked()}
        notifyStopped(stopped)
    }

    private fun notifyStopped(stopped:Stopped?) {
        if(stopped!=null) runCatching {onStopped(stopped.epoch,stopped.reason)}
    }

    private fun runWorker() {
        while(true) {
            pump()
            synchronized(lock) {
                if(closed) return
                val value=intent
                if(!isArmed || value==null) lock.wait()
                else {
                    val now=clock()
                    val expiresAt=minOf(updatedAt+INTENT_TTL_MS,telemetryAt+TELEMETRY_TTL_MS)
                    val due=if(value.active || lastSentActive) minOf(nextAt,expiresAt) else expiresAt
                    val delay=due-now
                    if(delay>0) lock.wait(delay)
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if(closed) return
            clear("app_closed",true)
            closed=true;activated=false;connection=null
            lock.notifyAll()
        }
    }
}
