package pl.gpschat

import org.json.JSONObject

data class GeoMessage(
    val id: String,
    val text: String,
    val latitude: Double,
    val longitude: Double,
    val createdAt: Long,
    val expiresAt: Long,
    val radiusM: Float = 100f,
    val mine: Boolean = false,
) {
    fun json() = JSONObject().put("id", id).put("text", text)
        .put("latitude", latitude).put("longitude", longitude)
        .put("created_at", createdAt).put("expires_at", expiresAt)
        .put("radius_m", radiusM.toDouble()).put("mine", mine)

    companion object {
        fun fromJson(j: JSONObject) = GeoMessage(
            j.getString("id"), j.getString("text"), j.getDouble("latitude"), j.getDouble("longitude"),
            j.getLong("created_at"), j.getLong("expires_at"), j.optDouble("radius_m", 100.0).toFloat(), j.optBoolean("mine")
        )
    }
}

object NotificationPolicy {
    fun shouldNotify(mine: Boolean, expiresAt: Long, seen: Boolean, now: Long) =
        !mine && !seen && expiresAt > now
}
