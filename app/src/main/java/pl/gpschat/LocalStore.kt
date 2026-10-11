package pl.gpschat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.SecureRandom

class LocalStore(context: Context) : ApiConfiguration {
    private val prefs = context.getSharedPreferences("gpschat", Context.MODE_PRIVATE)
    override var apiUrl: String
        get() = prefs.getString("api_url", "")!!
        set(value) { prefs.edit().putString("api_url", value).apply() }
    var monitoring: Boolean
        get() = prefs.getBoolean("monitoring", false)
        set(value) { prefs.edit().putBoolean("monitoring", value).apply() }

    override val token: String
        get() = synchronized(lock) {
            prefs.getString("installation_token", null) ?: run {
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val value = bytes.joinToString("") { "%02x".format(it) }
                check(prefs.edit().putString("installation_token", value).commit())
                value
            }
        }

    fun messages(): List<GeoMessage> = synchronized(lock) {
        runCatching {
            val array = JSONArray(prefs.getString("messages", "[]"))
            (0 until array.length()).map { GeoMessage.fromJson(array.getJSONObject(it)) }
                .filter { it.expiresAt > System.currentTimeMillis() / 1000 }
        }.getOrDefault(emptyList())
    }

    fun saveMessages(messages: List<GeoMessage>) = synchronized(lock) {
        val array = JSONArray().also { a -> messages.forEach { a.put(it.json()) } }
        prefs.edit().putString("messages", array.toString()).apply()
    }

    fun remove(id: String) { saveMessages(messages().filterNot { it.id == id }) }

    fun clearCache() = synchronized(lock) {
        prefs.edit().remove("messages").remove("seen").apply()
    }

    fun isSeen(id: String): Boolean = synchronized(lock) {
        JSONObject(prefs.getString("seen", "{}") ?: "{}").has(id)
    }

    fun markSeen(message: GeoMessage) = synchronized(lock) {
        val seen = JSONObject(prefs.getString("seen", "{}") ?: "{}")
        val now = System.currentTimeMillis() / 1000
        seen.keys().asSequence().toList().filter { seen.optLong(it) <= now }.forEach { seen.remove(it) }
        seen.put(message.id, message.expiresAt)
        prefs.edit().putString("seen", seen.toString()).commit()
    }

    fun validateUrl(input: String): String {
        val uri = runCatching { URI(input.trim().trimEnd('/')) }.getOrNull()
            ?: error("Podaj poprawny adres serwera.")
        require(uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/")) { "Podaj sam adres serwera, bez ścieżki i danych logowania." }
        require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http")) {
            "Serwer musi używać HTTPS. HTTP jest dostępne tylko w wersji testowej."
        }
        return uri.toString().trimEnd('/')
    }

    companion object { val lock = Any() }
}
