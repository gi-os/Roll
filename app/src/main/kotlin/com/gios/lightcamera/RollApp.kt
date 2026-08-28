package com.gios.lightcamera

import android.app.Application
import com.gios.light.common.report.LightReport

/**
 * Exists for one reason: to install the reporting stack before anything else can fall over.
 *
 * The handler has to be in place before the first activity is created, and `Application.onCreate`
 * is the only hook early enough. Nothing else belongs here — no singletons, no eager work — since
 * every millisecond spent in here is a millisecond before the viewfinder appears.
 */
class RollApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The library owns the whole reporting feature now — crash handler, shake, chip, sheet,
        // queue. One install here, one ReportOverlay line in the activity, and every piece of it
        // is the same code every Bright app runs, which is the point: Roll's local copy of the
        // shake path aged until it silently stopped offering, and nobody could tell from the
        // phone whether the gesture, the sensor or the wiring had died. Shared code ages in
        // public.
        LightReport.install(
            this,
            appName = "Roll",
            label = "roll",
            token = BuildConfig.REPORT_TOKEN,
        )
    }
}
