package pl.gpschat

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.android.gms.location.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

object Proximity {
    private val mutex = Mutex()
    fun fineLocation(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun backgroundLocation(context: Context) = fineLocation(context) && (Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
    fun notifications(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
    )

    @SuppressLint("MissingPermission")
    suspend fun register(context: Context, messages: List<GeoMessage>) = mutex.withLock {
        val store = LocalStore(context)
        val client = LocationServices.getGeofencingClient(context)
        client.removeGeofences(pending(context)).await()
        if (!store.monitoring || !backgroundLocation(context) || !notifications(context)) return@withLock
        val now = System.currentTimeMillis() / 1000
        val fences = messages.filter { NotificationPolicy.shouldNotify(it.mine, it.expiresAt, store.isSeen(it.id), now) }
            .take(100).map {
                Geofence.Builder().setRequestId(it.id).setCircularRegion(it.latitude, it.longitude, it.radiusM)
                    .setExpirationDuration((it.expiresAt - now) * 1000)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER).build()
            }
        if (fences.isNotEmpty()) {
            client.addGeofences(GeofencingRequest.Builder().setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(fences).build(), pending(context)).await()
        }
    }

    suspend fun stop(context: Context) = mutex.withLock {
        WorkManager.getInstance(context).cancelUniqueWork("nearby-periodic")
        WorkManager.getInstance(context).cancelUniqueWork("nearby-now")
        LocationServices.getGeofencingClient(context).removeGeofences(pending(context)).await()
    }

    fun schedule(context: Context, immediate: Boolean = false) {
        if (!LocalStore(context).monitoring) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("nearby-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NearbyWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
        if (immediate) WorkManager.getInstance(context).enqueueUniqueWork("nearby-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<NearbyWorker>().setConstraints(constraints).build())
    }
}

class NearbyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val store = LocalStore(applicationContext)
        if (!store.monitoring || store.apiUrl.isBlank() || !Proximity.backgroundLocation(applicationContext)) return Result.success()
        return try {
            val location = LocationServices.getFusedLocationProviderClient(applicationContext).lastLocation.await()
                ?: return Result.success()
            if ((android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) > TimeUnit.MINUTES.toNanos(30)) return Result.success()
            val url = store.apiUrl
            val messages = ApiClient(store).nearby(location.latitude, location.longitude)
            if (url != store.apiUrl || !store.monitoring) return Result.success()
            store.saveMessages(messages)
            Proximity.register(applicationContext, messages)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Proximity.schedule(context, immediate = true)
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        val store = LocalStore(context)
        if (!store.monitoring || !Proximity.backgroundLocation(context) || !Proximity.notifications(context)) return
        val ids = event.triggeringGeofences.orEmpty().map { it.requestId }.distinct().take(100)
        if (ids.isEmpty()) return
        // Validate against the live API before showing withdrawn or moderated content.
        WorkManager.getInstance(context).enqueueUniqueWork("arrival-${ids.sorted().hashCode()}", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ArrivalWorker>().setInputData(workDataOf("ids" to ids.toTypedArray()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}

class ArrivalWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val context = applicationContext
        val store = LocalStore(context)
        if (!store.monitoring || !Proximity.backgroundLocation(context) || !Proximity.notifications(context) || store.apiUrl.isBlank()) return Result.success()
        val ids = inputData.getStringArray("ids")?.toList().orEmpty()
        if (ids.isEmpty()) return Result.success()
        val url = store.apiUrl
        val messages = try { ApiClient(store).lookup(ids) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { return Result.retry() }
        if (url != store.apiUrl || !store.monitoring) return Result.success()
        synchronized(LocalStore.lock) {
            messages.forEach { message ->
                if (!NotificationPolicy.shouldNotify(message.mine, message.expiresAt, store.isSeen(message.id), System.currentTimeMillis() / 1000)) return@forEach
                // Keep the delivered message available when its notification is opened.
                store.saveMessages((store.messages() + message).distinctBy { it.id })
                val open = Intent(context, MainActivity::class.java).putExtra("message_id", message.id)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val click = PendingIntent.getActivity(context, message.id.hashCode(), open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = NotificationCompat.Builder(context, GpsChatApp.CHANNEL)
                    .setSmallIcon(R.drawable.ic_pin).setContentTitle("Ktoś zostawił tu wiadomość")
                    .setContentText(message.text).setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
                    .setContentIntent(click).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
                try {
                    NotificationManagerCompat.from(context).notify(message.id, 0, notification)
                    store.markSeen(message)
                } catch (_: SecurityException) { /* Permission may be revoked during delivery. */ }
            }
        }
        return Result.success()
    }
}
