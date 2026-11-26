package dev.drivelog.ride

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import dev.drivelog.pluginapi.CarPluginContract
import dev.drivelog.pluginapi.CarValuesBundle
import dev.drivelog.pluginapi.ICarDataSink
import dev.drivelog.pluginapi.ICarPlugin
import java.text.DateFormat
import java.util.Date

/**
 * The plugin as Drivelog sees it while a trip runs ([CarPluginContract]). It cannot read the vehicle during the ride:
 * the vehicle app gets the values only when the vehicle disconnects. So at the start it hands over what it read last
 * (the end of the previous ride, which is where this one starts), and a reading that comes in during the trip goes on
 * at once. The values from after the ride reach Drivelog through [ScreenReaderService].
 */
class RidePluginService : Service() {

    private val binder = object : ICarPlugin.Stub() {
        override fun apiVersion(): Int = CarPluginContract.API_VERSION
        override fun start(sink: ICarDataSink?) {
            if (sink == null) return
            synchronized(lock) { RidePluginService.sink = sink }
            send(sink, RideStore(this@RidePluginService).latest)
        }
        override fun stop() = stopSending()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        stopSending()
        return false
    }

    private fun stopSending() {
        val s = synchronized(lock) { sink.also { sink = null } }
        try { s?.onStatus(CarPluginContract.STATE_STOPPED, "") } catch (_: RemoteException) {}
        if (s != null) ScreenReaderService.rideEnded()     // the ride is over: its values are in the vehicle app now
    }

    companion object {
        private val lock = Any()
        private var sink: ICarDataSink? = null

        /** A new reading from the screen: to the trip that runs now, if any. */
        fun onNewReading(reading: RideReading) {
            val s = synchronized(lock) { sink } ?: return
            send(s, reading)
        }

        private fun send(s: ICarDataSink, reading: RideReading?) {
            try {
                if (reading == null) {
                    s.onStatus(CarPluginContract.STATE_NO_CAR, "no reading yet: open the vehicle app after the ride")
                } else {
                    s.onStatus(CarPluginContract.STATE_CONNECTED, "reading of " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(reading.atMs)))
                    s.onValues(CarValuesBundle.toBundle(reading.values))
                }
            } catch (e: RemoteException) {
                synchronized(lock) { if (sink === s) sink = null }     // Drivelog is gone
            }
        }
    }
}
