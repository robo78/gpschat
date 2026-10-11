package pl.gpschat

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Proximity.fineLocation(this)) model.refresh()
        else model.notice("Dokładna lokalizacja jest potrzebna do ustalenia miejsca wiadomości. Możesz ją włączyć w ustawieniach aplikacji.")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) model.notice("Powiadomienia są wyłączone. Możesz włączyć je w ustawieniach aplikacji.")
    }
    private val backgroundPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) model.notice("Lokalizacja w tle nie została włączona.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model.openNotification(intent.getStringExtra("message_id"))
        setContent {
            GpsChatTheme {
                GpsChatScreen(model, requestLocation = ::requestLocation,
                    requestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33 && !Proximity.notifications(this))
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else openSettings()
                    }, requestBackground = {
                        if (!Proximity.fineLocation(this)) requestLocation()
                        else if (Build.VERSION.SDK_INT == 29) backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        else openSettings()
                    }, openSettings = ::openSettings)
            }
        }
    }
    private fun requestLocation() {
        if (Proximity.fineLocation(this)) model.refresh()
        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    private fun openSettings() { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.openNotification(intent.getStringExtra("message_id"))
    }
    override fun onResume() { super.onResume(); model.onResume() }
}
