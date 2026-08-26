package com.gios.lightcamera

import android.app.Application
import com.gios.light.common.report.LightReport

/**
 * Exists for one reason: to arm reporting before anything else can fall over.
 *
 * The crash handler has to be in place before the first activity is created, and
 * `Application.onCreate` is the only hook early enough — `LightReport.install` arms it as part of
 * naming the app, so this is still one call. Nothing else belongs here — no singletons, no eager
 * work — since every millisecond spent in here is a millisecond before the viewfinder appears.
 */
class RollApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LightReport.install(
            context = this,
            appName = "Roll",
            label = "roll",
            token = BuildConfig.REPORT_TOKEN,
        )
    }
}
