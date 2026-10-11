package pl.gpschat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

interface ApiConfiguration {
    val apiUrl: String
    val token: String
}

class ApiClient(private val store: ApiConfiguration, private val baseUrl: String? = null) {
    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val address = baseUrl ?: store.apiUrl
        check(address.isNotBlank()) { "Najpierw ustaw adres serwera w Ustawieniach." }
        val connection = (URL(address + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = false // Never forward the installation credential to another host.
            setRequestProperty("Authorization", "Bearer ${store.token}")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { error("Serwer nie zwrócił poprawnej odpowiedzi (HTTP $status).") }
            check(status in 200..299) { json.optString("error", "Błąd serwera: $status") }
            json
        } finally { connection.disconnect() }
    }

    suspend fun health() { check(request("GET", "/health").optString("status") == "ok") { "Serwer nie jest gotowy." } }
    suspend fun nearby(lat: Double, lon: Double): List<GeoMessage> {
        val array = request("GET", "/messages?lat=$lat&lon=$lon").getJSONArray("messages")
        return (0 until array.length()).map { GeoMessage.fromJson(array.getJSONObject(it)) }
    }
    suspend fun lookup(ids: List<String>): List<GeoMessage> {
        val array = request("POST", "/messages/lookup", JSONObject().put("ids", JSONArray(ids))).getJSONArray("messages")
        return (0 until array.length()).map { GeoMessage.fromJson(array.getJSONObject(it)) }
    }
    suspend fun post(text: String, lat: Double, lon: Double) = GeoMessage.fromJson(
        request("POST", "/messages", JSONObject().put("text", text).put("latitude", lat).put("longitude", lon))
    )
    suspend fun delete(id: String) { request("DELETE", "/messages/$id") }
    suspend fun report(id: String) { request("POST", "/messages/$id/report", JSONObject()) }
}
