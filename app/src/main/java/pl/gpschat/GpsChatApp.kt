package pl.gpschat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.osmdroid.config.Configuration

class GpsChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().apply {
            userAgentValue = "GPSChat/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"
            osmdroidBasePath = java.io.File(cacheDir, "maps")
            osmdroidTileCache = java.io.File(cacheDir, "maps/tiles")
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Wiadomości w pobliżu", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Wiadomości zostawione w miejscach, które odwiedzasz"
            }
        )
    }
    companion object { const val CHANNEL = "nearby_messages" }
}
