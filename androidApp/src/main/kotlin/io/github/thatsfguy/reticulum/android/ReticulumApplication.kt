package io.github.thatsfguy.reticulum.android

import android.app.Application
import android.preference.PreferenceManager
import org.osmdroid.config.Configuration

/**
 * Application initializer. Sets the osmdroid user-agent so OSM tile
 * requests are not blocked by the upstream tile server's anti-abuse
 * filter, and points osmdroid at the app's preference store.
 */
class ReticulumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        @Suppress("DEPRECATION")
        Configuration.getInstance().apply {
            load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
            userAgentValue = packageName
        }
    }
}
