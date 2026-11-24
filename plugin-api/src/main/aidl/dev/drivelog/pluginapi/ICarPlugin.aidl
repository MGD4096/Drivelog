// Implemented by a plugin app's service (CarPluginContract.ACTION), called by Drivelog.
package dev.drivelog.pluginapi;

import dev.drivelog.pluginapi.ICarDataSink;

interface ICarPlugin {
    // CarPluginContract.API_VERSION of the plugin's build.
    int apiVersion();
    // Start reading the car and report to sink until stop(). Called when a trip starts; a second start replaces the sink.
    void start(ICarDataSink sink);
    // Stop reading the car. Drivelog also unbinds; a plugin must stop when its last client goes away.
    void stop();
}
