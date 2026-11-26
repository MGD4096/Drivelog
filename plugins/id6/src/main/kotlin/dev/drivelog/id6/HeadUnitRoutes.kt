package dev.drivelog.id6

import dev.drivelog.pluginapi.CarPluginContract
import java.io.IOException
import java.net.Socket

/**
 * One way of reaching the head-unit plugin. [BridgeClient] does not care which: it gets a connected socket from
 * [HeadUnitRoutes], which tries every route in turn. A new way (another port, USB) is a new route, nothing else changes.
 */
interface HeadUnitRoute {
    /** A connected socket, or null when nobody is there this way right now. May throw [IOException]. */
    fun connect(): Socket?

    /** One line for the plugin's own screen: what this way is doing. */
    fun describe(): String

    /** Frees what the route holds (a listening socket, a network callback). */
    fun close()
}

/** The routes in order of preference: the first that gives a socket wins. A route's failure does not stop the others. */
class HeadUnitRoutes(private val routes: List<HeadUnitRoute>) {
    /** Why each route failed last time (by position), shown with [describe]. Written on the client's thread, read on another. */
    private val errors = arrayOfNulls<String>(routes.size)

    private fun setError(i: Int, e: String?) = synchronized(errors) { errors[i] = e }

    fun connect(): Socket? {
        for ((i, r) in routes.withIndex()) {
            val s = try {
                r.connect().also { setError(i, null) }
            } catch (e: IOException) {
                setError(i, e.message ?: e.javaClass.simpleName)
                null
            }
            if (s != null) return s
        }
        return null
    }

    fun describe(): String {
        val failed = synchronized(errors) { errors.copyOf() }
        return routes.mapIndexed { i, r -> r.describe() + (failed[i]?.let { " ($it)" } ?: "") }.joinToString("\n")
    }

    fun close() = routes.forEach { it.close() }
}

/** The head unit calls the phone ([HeadUnitCalls]); waits up to [waitMs] each time. [openError] when the port was taken. */
class CallsRoute(private val calls: HeadUnitCalls?, private val openError: String?, private val waitMs: Int) : HeadUnitRoute {
    override fun connect(): Socket? = calls?.accept(waitMs)

    override fun describe(): String = if (calls != null) {
        "listening for the head unit on port ${calls.boundPort}: ${calls.calls} call(s)" + (calls.lastCaller?.let { ", last from $it" } ?: "")
    } else {
        "not listening for the head unit" + (openError?.let { " ($it)" } ?: "")
    }

    override fun close() { calls?.close() }

    companion object {
        /** Opens the listening port; when it is taken the route stays, saying why it does nothing. */
        fun open(waitMs: Int): CallsRoute = try {
            CallsRoute(HeadUnitCalls(), null, waitMs)
        } catch (e: IOException) {
            CallsRoute(null, e.message ?: e.javaClass.simpleName, waitMs)
        }
    }
}

/** What the plugin reports to Drivelog for the state of its [BridgeClient]: a [CarPluginContract] state and its text. */
object PluginStatus {
    fun of(state: BridgeClient.State, headUnit: String?, lastError: String?): Pair<Int, String> = when (state) {
        BridgeClient.State.CONNECTED -> CarPluginContract.STATE_CONNECTED to (headUnit ?: "") +
            if (BridgeVersion.isOutdated(headUnit)) " (update the head-unit plugin to ${BridgeVersion.MIN} or newer)" else ""
        BridgeClient.State.NO_NETWORK -> CarPluginContract.STATE_NO_CAR to ""
        BridgeClient.State.STOPPED -> CarPluginContract.STATE_STOPPED to ""
        BridgeClient.State.CONNECTING -> lastError?.let { CarPluginContract.STATE_ERROR to it } ?: (CarPluginContract.STATE_CONNECTING to "")
    }
}
