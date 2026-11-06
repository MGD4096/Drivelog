package dev.idrivetrips.core

import java.net.URLDecoder

class HttpResponse(val status: Int, val contentType: String, val body: ByteArray) {
    companion object {
        fun text(status: Int, text: String) = HttpResponse(status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
    }
}

/**
 * The phone side of the head-unit plugin protocol (port 8765). Only two requests matter:
 *
 *  - `GET /ponte`  The plugin finds the phone by asking this on every candidate address, and then polls it. The answer
 *    must start with `IDRIVEPONTE`. `avvio=<number>` ("start") must be non-zero and must change whenever this app
 *    starts afresh: the plugin then re-sends every car value it already sent, so a restarted app is not left empty.
 *    All page counters (`meteo=`, `notizie=` ...) are 0, which tells the plugin there is nothing to download.
 *
 *  - `GET /auto?n=<property>&v=<url-encoded JSON>`  One car value. The plugin sends the next one only after a 200.
 *    Any other status except 404 makes it retry the same value forever (and so blocks all values behind it), and a 404
 *    makes it give up on car data until the app restarts. So a value for a known property always gets 200, even if
 *    its content cannot be used; only a request that no plugin would send (unknown property, missing parameters) gets 400.
 */
class BridgeProtocol(
    private val car: CarState,
    private val appVersion: String,
    startedAtEpochSeconds: Long,
) {
    /** Unsigned 32-bit and never 0, which is how the plugin reads it. */
    private val started: Long = startedAtEpochSeconds.coerceIn(1L, 0xFFFF_FFFFL)

    fun handle(method: String, target: String): HttpResponse {
        if (method != "GET") return HttpResponse.text(405, "METHOD NOT ALLOWED\n")
        val path = target.substringBefore('?')
        val query = if ('?' in target) target.substringAfter('?') else ""
        return when (path) {
            "/ponte" -> HttpResponse.text(
                200,
                "IDRIVEPONTE $appVersion meteo=0 notizie=0 n=0 locali=0 carburanti=0 traffico=0 auto=0 vettura=0 avvio=$started\n",
            )
            "/auto" -> receiveCarValue(query)
            else -> HttpResponse.text(404, "NOT FOUND\n")
        }
    }

    private fun receiveCarValue(query: String): HttpResponse {
        var name: String? = null
        var value: String? = null
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=')
            val decoded = try {
                URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            } catch (e: IllegalArgumentException) {
                return HttpResponse.text(400, "BAD REQUEST\n")
            }
            if (key == "n") name = decoded else if (key == "v") value = decoded
        }
        if (name == null || value == null) return HttpResponse.text(400, "BAD REQUEST\n")
        return when (car.receive(name, value)) {
            CarState.Result.STORED -> HttpResponse.text(200, "OK\n")
            CarState.Result.IGNORED_BAD_VALUE -> HttpResponse.text(200, "IGNORED\n")
            CarState.Result.UNKNOWN_PROPERTY -> HttpResponse.text(400, "UNKNOWN PROPERTY\n")
        }
    }
}
