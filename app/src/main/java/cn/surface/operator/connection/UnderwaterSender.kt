package cn.surface.operator.connection

import cn.surface.operator.core.*

/** H7 writer owns wire cadence. Only main-thread snapshots renew the GUI lease. */
class UnderwaterSender(
    private val clock:()->Long,
    private val onStopped:(Int,String)->Unit,
    autoStart:Boolean=true
):AutoCloseable {
    companion object {const val PERIOD_MS=50L;const val INTENT_TTL_MS=250L}
    private val lock=Object()
    private var epoch=-1
    private var connection:GatewayConnection?=null
    private var activated=false
    private var armed=false
    private var expired=false
    private var reason=""
    private var intent:UnderwaterIntent?=null
    private var neutral:UnderwaterIntent?=null
    private var updatedAt=-1L
    private var feedbackDeadline=0.0
    private var nextAt=0L
    private var lastInputAt:Long?=null
    private var sequence=0L
    private var revision=0L
    private var closed=false
    private val thread=if(autoStart) Thread(::run,"operator-underwater-sender").apply {isDaemon=true;start()} else null
    val isExpired get()=synchronized(lock) {expired}
    val stopReason get()=synchronized(lock) {reason}
    val isArmed get()=synchronized(lock) {armed}
    val hasPending get()=synchronized(lock) {armed || intent!=null || neutral!=null}

    fun bind(value:Int,target:GatewayConnection?)=synchronized(lock) {
        if(closed || value<epoch) return@synchronized
        cancelLocked();epoch=value;connection=target;activated=false;expired=false;reason="";lastInputAt=null
        lock.notifyAll()
    }
    fun activate(value:Int):Boolean=synchronized(lock) {
        if(closed || value!=epoch || connection==null) return@synchronized false
        cancelLocked();activated=true;true
    }
    /** Explicit fresh touch only; never invoked by timer or telemetry. */
    fun arm(value:Int):Boolean=synchronized(lock) {
        if(closed || value!=epoch || !activated || connection==null) return@synchronized false
        // A quick new press must not erase the preceding gesture's neutral frame.
        val pendingNeutral=neutral
        cancelLocked();neutral=pendingNeutral;armed=true;expired=false;reason=""
        nextAt=nextInputTime(clock());lock.notifyAll();true
    }
    /** Normal release is one ordered neutral input, without resetting server generation. */
    fun release(value:Int,generation:Long):Boolean {
        var stopped:Pair<Int,String>?=null
        val accepted=synchronized(lock) {
            if(closed || value!=epoch || !activated || expired || connection==null || generation<1)
                return@synchronized false
            val now=clock()
            val failure=expiryLocked(now)
            if(failure!=null) {stopped=expireLocked(failure);return@synchronized false}
            if(!armed && intent==null) return@synchronized true
            cancelLocked()
            neutral=UnderwaterIntent("STOP",0,generation)
            nextAt=nextInputTime(now);lock.notifyAll();true
        }
        stopped?.let {onStopped(it.first,it.second)}
        return accepted
    }
    fun update(value:Int,newIntent:UnderwaterIntent,reportDeadline:Double):Boolean {
        var stopped:Pair<Int,String>?=null
        val accepted=synchronized(lock) {
            if(closed || value!=epoch || !activated || !armed || expired) return@synchronized false
            val now=clock()
            val failure=expiryLocked(now) ?: if(!reportDeadline.isFinite() || now.toDouble()>=reportDeadline) "h7_feedback_expired" else null
            if(failure!=null) {stopped=expireLocked(failure);return@synchronized false}
            if(newIntent.direction=="STOP" || newIntent.speed==0) return@synchronized false
            if(newIntent!=intent) {revision++;nextAt=nextInputTime(now)}
            intent=newIntent;updatedAt=now;feedbackDeadline=reportDeadline
            lock.notifyAll();true
        }
        stopped?.let {onStopped(it.first,it.second)}
        return accepted
    }
    fun checkExpiry(value:Int) {
        val stopped=synchronized(lock) {
            if(value!=epoch || !armed || closed) null else expiryLocked(clock())?.let(::expireLocked)
        }
        stopped?.let {onStopped(it.first,it.second)}
    }
    fun clear(reason:String,sendStop:Boolean=true):Boolean=synchronized(lock) {
        val had=armed || intent!=null || neutral!=null
        val target=connection
        cancelLocked()
        if(sendStop && had && activated && !closed) runCatching {target?.send(underwaterPacket("op" to "manual_stop","reason" to reason))}
        lock.notifyAll();had
    }
    private fun nextInputTime(now:Long)=maxOf(now,lastInputAt?.plus(PERIOD_MS) ?: now)
    private fun cancelLocked() {revision++;armed=false;intent=null;neutral=null;updatedAt=-1;feedbackDeadline=0.0;nextAt=0}
    private fun expiryLocked(now:Long):String? {
        if(intent==null) return null
        if(now<updatedAt || now-updatedAt>=INTENT_TTL_MS) return "gui_input_expired"
        if(now.toDouble()>=feedbackDeadline) return "h7_feedback_expired"
        return null
    }
    private fun expireLocked(reason:String):Pair<Int,String> {
        val value=epoch;val target=connection
        cancelLocked();expired=true;this.reason=reason
        runCatching {target?.send(underwaterPacket("op" to "manual_stop","reason" to reason))}
        lock.notifyAll();return value to reason
    }
    private fun stepLocked():Pair<Int,String>? {
        if(closed || !activated || expired) return null
        val now=clock()
        expiryLocked(now)?.let {return expireLocked(it)}
        val sendingNeutral=neutral!=null
        val value=(neutral ?: if(armed) intent else null) ?: return null
        if(now<nextAt) return null
        val target=connection ?: return expireLocked("send_not_ready")
        val before=revision
        val sent=runCatching {target.send(value.encode(++sequence))}.getOrDefault(false)
        if(revision!=before || connection!==target) return null
        if(!sent) return expireLocked("send_not_ready")
        if(sendingNeutral) neutral=null
        lastInputAt=now
        nextAt=now+PERIOD_MS
        return null
    }
    internal fun pump() {
        val stopped=synchronized(lock) {stepLocked()}
        stopped?.let {runCatching {onStopped(it.first,it.second)}}
    }
    private fun run() {
        while(true) {
            pump()
            synchronized(lock) {
                if(closed) return
                if(neutral==null && (!armed || intent==null)) lock.wait()
                else {
                    val deadline=if(armed && intent!=null)
                        minOf(nextAt.toDouble(),(updatedAt+INTENT_TTL_MS).toDouble(),feedbackDeadline)
                        else nextAt.toDouble()
                    val delay=kotlin.math.ceil(deadline-clock()).toLong()
                    if(delay>0) lock.wait(delay)
                }
            }
        }
    }
    override fun close()=synchronized(lock) {
        if(closed) return@synchronized
        clear("app_closed",true);closed=true;activated=false;connection=null;lock.notifyAll()
    }
}
