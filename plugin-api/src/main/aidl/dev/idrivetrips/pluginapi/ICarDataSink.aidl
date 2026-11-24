// Implemented by Drivelog, called by the plugin. oneway: the plugin never waits for Drivelog.
package dev.idrivetrips.pluginapi;

import android.os.Bundle;

oneway interface ICarDataSink {
    // Everything the plugin knows about the car right now (CarValuesBundle). Each call replaces the last.
    void onValues(in Bundle values);
    // The plugin's connection to the car: one of CarPluginContract.STATE_*, and a short text for the person
    // (the head unit's name when connected, the reason when not).
    void onStatus(int state, String text);
}
