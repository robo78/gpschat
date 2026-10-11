package pl.gpschat

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.DateFormat
import java.util.Date
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

private val Green = Color(0xFF167D63)

@Composable
fun GpsChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Green, secondary = Color(0xFF527A6F),
        background = Color(0xFFF5F8F6), surface = Color.White, primaryContainer = Color(0xFFD9F2E7)), content = content)
}

@Composable
fun GpsChatScreen(model: MainViewModel, requestLocation: () -> Unit, requestNotifications: () -> Unit,
                  requestBackground: () -> Unit, openSettings: () -> Unit) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    var settings by remember { mutableStateOf(false) }
    var composer by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(true) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(it); model.clearNotice() }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Place, null, tint = Green, modifier = Modifier.size(32.dp))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("GPSChat", fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    Text("Wiadomości mają swoje miejsce", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, "Ustawienia") }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
                when {
                    state.busy -> Unit
                    state.server.isBlank() -> settings = true
                    !Proximity.fineLocation(context) -> requestLocation()
                    else -> composer = true
                }
            }, icon = { Icon(Icons.Outlined.AddLocationAlt, null) }, text = { Text("Zostaw wiadomość") },
                containerColor = Green, contentColor = Color.White)
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(selected = showMap, onClick = { showMap = true },
                    icon = { Icon(Icons.Outlined.Map, null) }, label = { Text("Mapa") })
                NavigationBarItem(selected = !showMap, onClick = { showMap = false },
                    icon = { Icon(Icons.Outlined.ChatBubbleOutline, null) }, label = { Text("W pobliżu") })
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("Odkryj, co zostawili inni", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Krótka myśl, wskazówka albo historia.\nZostaw ją tutaj, dla kogoś po drodze.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.server.isBlank()) item {
                InfoCard("Połącz się ze wspólną bazą", "W Ustawieniach wpisz adres serwera GPSChat. Bez konta, bez logowania.",
                    "Ustaw serwer", { settings = true })
            }
            if (!Proximity.fineLocation(context)) item {
                InfoCard("Gdzie jesteś?", "Zezwól na dokładną lokalizację, aby zobaczyć wiadomości w okolicy i zostawić własną.",
                    "Udostępnij lokalizację", requestLocation)
            }
            if (showMap) item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 2.dp) {
                        NearbyMap(state.location, state.messages, model::select, Modifier.fillMaxWidth().height(280.dp))
                    }
                    Text("© OpenStreetMap contributors", modifier = Modifier.padding(top = 4.dp).clickable {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.openstreetmap.org/copyright")))
                    }, style = MaterialTheme.typography.labelSmall)
                    if (state.location == null) Text("Ustal lokalizację, aby wyśrodkować mapę na swoim miejscu.",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("W okolicy · ${state.messages.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Wpisy do 10 km · alert po wejściu w 100 m", style = MaterialTheme.typography.labelSmall)
                    }
                    if (state.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = requestLocation) { Icon(Icons.Outlined.Refresh, "Odśwież lokalizację i wiadomości") }
                }
            }
            if (state.messages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.MarkChatUnread, null, tint = Green, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Tutaj zaczyna się historia", fontWeight = FontWeight.SemiBold)
                    Text("Pobierz wpisy lub zostaw pierwszą wiadomość.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(state.messages, key = { it.id }) { message ->
                MessageCard(message, state.location, { model.select(message) })
            }
            item {
                Text(if (state.monitoring && Proximity.backgroundLocation(context) && Proximity.notifications(context))
                    "Alerty włączone. Android może dostarczać je z opóźnieniem." else "Alerty w tle są wyłączone lub wymagają uprawnień. Włącz je w Ustawieniach.",
                    modifier = Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (settings) SettingsDialog(state, model, { settings = false }, requestLocation, requestNotifications, requestBackground, openSettings)
    if (composer) ComposeDialog(state.busy, state.notice, { if (!state.busy) composer = false }) { text -> model.post(text) { composer = false } }
    state.selected?.let { message ->
        DetailDialog(message, state.busy, { model.select(null) }, { model.delete(message) }, { model.report(message) })
    }
}

@Composable
private fun InfoCard(title: String, text: String, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun MessageCard(message: GeoMessage, location: Location?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Place, null, tint = Green, modifier = Modifier.size(18.dp))
                Text(if (message.mine) "Twoja wiadomość" else "Od kogoś po drodze", style = MaterialTheme.typography.labelMedium,
                    color = Green, modifier = Modifier.weight(1f).padding(start = 6.dp))
                Text(distanceLabel(location, message), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(message.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text("Wygasa ${dateLabel(message.expiresAt)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun dateLabel(seconds: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(seconds * 1000))
private fun distanceLabel(location: Location?, message: GeoMessage): String {
    if (location == null) return "100 m zasięgu"
    val results = FloatArray(1)
    Location.distanceBetween(location.latitude, location.longitude, message.latitude, message.longitude, results)
    return if (results[0] < 1000) "${results[0].toInt()} m" else "%.1f km".format(results[0] / 1000)
}

@Composable
private fun ComposeDialog(busy: Boolean, notice: String?, dismiss: () -> Unit, send: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, icon = { Icon(Icons.Outlined.AddLocationAlt, null) },
        title = { Text("Zostaw coś po sobie") }, text = {
            Column {
                Text("Wiadomość trafi do Twojego aktualnego miejsca. Będzie publiczna przez 7 dni.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = text, onValueChange = { if (it.length <= 500) text = it },
                    label = { Text("Twoja wiadomość") }, placeholder = { Text("Na przykład: za rogiem jest piękny widok…") },
                    minLines = 4, maxLines = 6, modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    supportingText = { Text("${text.length}/500 · nie publikuj danych osobowych") })
                if (notice != null) Text(notice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { Button(onClick = { send(text) }, enabled = !busy && text.isNotBlank()) {
            Text(if (busy) "Zapisywanie…" else "Zostaw tutaj")
        } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Anuluj") } })
}

@Composable
private fun DetailDialog(message: GeoMessage, busy: Boolean, dismiss: () -> Unit, delete: () -> Unit, report: () -> Unit) {
    var confirming by remember(message.id) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, icon = { Icon(Icons.Outlined.ChatBubbleOutline, null) },
        title = { Text(if (message.mine) "Twoja wiadomość" else "Wiadomość z tego miejsca") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(message.text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Text("Zasięg powiadomienia: 100 m\nWygasa: ${dateLabel(message.expiresAt)}", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = dismiss) { Text("Zamknij") } },
        dismissButton = { TextButton(onClick = { confirming = true }, enabled = !busy) {
            Text(if (message.mine) "Usuń" else "Zgłoś treść")
        } })
    if (confirming) AlertDialog(onDismissRequest = { confirming = false },
        title = { Text(if (message.mine) "Usunąć wiadomość?" else "Zgłosić wiadomość?") },
        text = { Text(if (message.mine) "Wpis zostanie usunięty ze wspólnej bazy." else "Moderator otrzyma zgłoszenie tej treści. Użyj tej opcji w przypadku spamu lub nadużyć.") },
        confirmButton = { TextButton(onClick = { confirming = false; if (message.mine) delete() else report() }) { Text("Potwierdź") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Anuluj") } })
}

@Composable
private fun SettingsDialog(state: HomeState, model: MainViewModel, dismiss: () -> Unit, requestLocation: () -> Unit,
                           requestNotifications: () -> Unit, requestBackground: () -> Unit, openSettings: () -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(state.server) }
    AlertDialog(onDismissRequest = { if (!state.busy) dismiss() }, title = { Text("Ustawienia GPSChat") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Bez konta i logowania", fontWeight = FontWeight.Bold)
            Text("Losowy klucz zapisany na telefonie pozwala usuwać własne wpisy. Po odinstalowaniu aplikacji utracisz tę możliwość.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, label = { Text("Adres API GPSChat") },
                placeholder = { Text("https://twoj-serwer.pl") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            Text("Adres musi prowadzić do naszego API, nie do zwykłej strony. W wersji testowej można użyć HTTP w sieci lokalnej.", style = MaterialTheme.typography.bodySmall)
            if (state.notice != null) Text(state.notice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Text("Powiadomienia podczas spaceru", fontWeight = FontWeight.Bold)
            Text("Za Twoją zgodą aplikacja używa lokalizacji także po zamknięciu. Pobiera pobliskie wpisy; serwer nie zapisuje historii Twojej trasy. Powiadomienia mogą pojawiać się z opóźnieniem.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = requestLocation) { Text(if (Proximity.fineLocation(context)) "✓ Dokładna lokalizacja" else "1. Zezwól na dokładną lokalizację") }
            TextButton(onClick = requestNotifications) { Text(if (Proximity.notifications(context)) "✓ Powiadomienia" else "2. Zezwól na powiadomienia") }
            TextButton(onClick = requestBackground) { Text(if (Proximity.backgroundLocation(context)) "✓ Lokalizacja w tle" else "3. Lokalizacja → Zezwalaj cały czas") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Alerty w pobliżu", modifier = Modifier.weight(1f))
                Switch(checked = state.monitoring, onCheckedChange = model::setMonitoring, enabled = !state.busy)
            }
            Text("Wymagane usługi Google Play. Wpisy w okolicy odświeżają się przy otwarciu/odświeżeniu oraz okresowo w tle. Android może ograniczać pracę w tle.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = openSettings) { Text("Otwórz uprawnienia systemowe") }
            Spacer(Modifier.height(8.dp))
            Text("Prywatność", fontWeight = FontWeight.Bold)
            Text("Treść i miejsce wpisu są publiczne. Pobieranie wpisów wysyła bieżące współrzędne do operatora serwera. Mapy pobiera OpenStreetMap, a geofencing obsługują usługi Google Play. Nie ma śledzenia innych osób ani listy odwiedzających.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        Button(onClick = { model.saveServer(url, dismiss) }, enabled = !state.busy && url.isNotBlank()) { Text(if (state.busy) "Sprawdzanie…" else "Sprawdź i zapisz") }
    }, dismissButton = { TextButton(onClick = dismiss, enabled = !state.busy) { Text("Zamknij") } })
}

@Composable
private fun NearbyMap(location: Location?, messages: List<GeoMessage>, select: (GeoMessage) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val map = remember { MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        controller.setZoom(15.0)
        controller.setCenter(GeoPoint(52.2297, 21.0122))
        zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
    } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(map, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) map.onResume()
            if (event == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        lifecycle.addObserver(observer)
        map.onResume()
        onDispose { lifecycle.removeObserver(observer); map.onPause(); map.onDetach() }
    }
    var lastCenter by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    AndroidView(factory = { map }, modifier = modifier, update = { view ->
        val point = location?.let { it.latitude to it.longitude }
        if (point != null && point != lastCenter) {
            view.controller.setCenter(GeoPoint(point.first, point.second)); lastCenter = point
        }
        view.overlays.clear()
        messages.forEach { message ->
            val center = GeoPoint(message.latitude, message.longitude)
            view.overlays.add(Polygon(view).apply {
                points = Polygon.pointsAsCircle(center, message.radiusM.toDouble())
                fillPaint.color = android.graphics.Color.argb(35, 22, 125, 99)
                outlinePaint.color = android.graphics.Color.rgb(22, 125, 99)
                outlinePaint.strokeWidth = 2f
            })
            view.overlays.add(Marker(view).apply {
                position = center
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = message.text
                setOnMarkerClickListener { _, _ -> select(message); true }
            })
        }
        location?.let {
            view.overlays.add(Marker(view).apply {
                position = GeoPoint(it.latitude, it.longitude)
                title = "Jesteś tutaj"
                icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_pin)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
        }
        view.invalidate()
    })
}
