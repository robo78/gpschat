package pl.gpschat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.location.Location
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

data class HomeState(
    val messages: List<GeoMessage> = emptyList(),
    val location: Location? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val selected: GeoMessage? = null,
    val server: String = "",
    val monitoring: Boolean = false,
    val permissionRevision: Long = 0,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    val store = LocalStore(context)
    private val api = ApiClient(store)
    private val mutable = MutableStateFlow(HomeState(messages = store.messages(), server = store.apiUrl, monitoring = store.monitoring))
    val state = mutable.asStateFlow()
    private fun update(block: (HomeState) -> HomeState) { mutable.value = block(mutable.value) }

    private fun operation(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        viewModelScope.launch {
            update { it.copy(busy = true) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice(e.message ?: "Nie udało się wykonać operacji. Sprawdź połączenie.") }
            finally { update { it.copy(busy = false) } }
        }
    }

    fun notice(text: String) { update { it.copy(notice = text) } }
    fun clearNotice() { update { it.copy(notice = null) } }
    fun select(message: GeoMessage?) { update { it.copy(selected = message) } }
    fun openNotification(id: String?) {
        if (id == null) return
        val message = store.messages().firstOrNull { it.id == id }
        if (message != null) select(message) else notice("Ta wiadomość wygasła lub nie jest już dostępna.")
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location {
        check(Proximity.fineLocation(context)) { "Włącz dokładną lokalizację, aby zostawić wiadomość w tym miejscu." }
        val cancellation = CancellationTokenSource()
        try {
            val location = withTimeout(20_000) {
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token).await()
            } ?: error("Nie można ustalić lokalizacji. Włącz GPS i spróbuj na zewnątrz.")
            check((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) < 120_000_000_000L) {
                "Lokalizacja jest zbyt stara. Spróbuj ponownie."
            }
            update { it.copy(location = location) }
            return location
        } catch (_: TimeoutCancellationException) {
            error("GPS nie odpowiedział na czas. Włącz lokalizację i spróbuj na zewnątrz.")
        } finally { cancellation.cancel() }
    }

    private suspend fun load(location: Location) {
        val messages = api.nearby(location.latitude, location.longitude)
        store.saveMessages(messages)
        update { it.copy(messages = messages, selected = it.selected?.let { old -> messages.firstOrNull { m -> m.id == old.id } }) }
        if (store.monitoring && Proximity.backgroundLocation(context)) {
            try { Proximity.register(context, messages) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice("Wpisy pobrane, ale nie udało się włączyć alertów. Sprawdź GPS i usługi Google Play.") }
        }
    }

    fun refresh() = operation {
        val location = currentLocation()
        load(location)
    }

    fun post(text: String, onPosted: () -> Unit) = operation {
        check(text.trim().length in 1..500) { "Wpisz od 1 do 500 znaków." }
        val location = currentLocation()
        check(location.accuracy <= 100) { "GPS jest za mało dokładny (${location.accuracy.toInt()} m). Spróbuj ponownie na zewnątrz." }
        val message = api.post(text.trim(), location.latitude, location.longitude)
        // Publishing succeeded even if the subsequent refresh fails.
        store.saveMessages((store.messages() + message).distinctBy { it.id })
        update { it.copy(messages = store.messages()) }
        onPosted()
        notice("Wiadomość została zostawiona na 7 dni.")
        try { load(location) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice("Wiadomość zapisana. Odśwież mapę, aby pobrać pozostałe wpisy.") }
    }

    fun delete(message: GeoMessage) = operation {
        api.delete(message.id)
        store.remove(message.id)
        update { it.copy(messages = store.messages(), selected = null) }
        notice("Wiadomość usunięta.")
    }

    fun report(message: GeoMessage) = operation {
        api.report(message.id)
        select(null)
        notice("Zgłoszenie przekazane do moderacji.")
    }

    fun saveServer(input: String, onSaved: () -> Unit) = operation {
        val url = store.validateUrl(input)
        // Validate before replacing the working configuration.
        val old = store.apiUrl
        ApiClient(store, url).health()
        store.apiUrl = url
        if (old != url) {
            store.clearCache()
            runCatching { Proximity.stop(context) }
        }
        update { it.copy(server = url, messages = store.messages(), selected = null) }
        if (store.monitoring) Proximity.schedule(context, immediate = true)
        notice("Połączenie z serwerem działa.")
        onSaved()
    }

    fun setMonitoring(enabled: Boolean) = operation {
        if (enabled) {
            check(store.apiUrl.isNotBlank()) { "Najpierw ustaw adres serwera." }
            check(Proximity.backgroundLocation(context) && Proximity.notifications(context)) {
                "Zezwól na lokalizację cały czas i na powiadomienia."
            }
            // Register first so an error cannot be presented as successful activation.
            store.monitoring = true
            try { Proximity.register(context, store.messages()) }
            catch (e: Exception) { store.monitoring = false; throw e }
            Proximity.schedule(context, immediate = true)
        } else {
            store.monitoring = false
            update { it.copy(monitoring = false) }
            Proximity.stop(context)
        }
        update { it.copy(monitoring = store.monitoring) }
    }

    fun onResume() {
        update { it.copy(messages = store.messages(), monitoring = store.monitoring, permissionRevision = System.nanoTime()) }
        if (store.monitoring && Proximity.backgroundLocation(context)) Proximity.schedule(context, immediate = true)
        if (store.apiUrl.isNotBlank() && Proximity.fineLocation(context) && !mutable.value.busy) refresh()
    }
}
