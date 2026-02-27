package com.example.execu_chat

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONObject
import androidx.core.content.edit
//stores jwt access + refresh tokens in SharedPref
// extrct id for databases
object TokenManager {
    private const val PREFS    = "auth_prefs"
    private const val KEY_ACCESS  = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_USER    = "username"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Store ────────────────────────────────────────────────────────
    fun save(ctx: Context, accessToken: String, refreshToken: String) {
        val username = extractUsername(accessToken)
        prefs(ctx).edit {
            putString(KEY_ACCESS, accessToken)
            putString(KEY_REFRESH, refreshToken)
            putString(KEY_USER, username)
        }
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    // ── Read ─────────────────────────────────────────────────────────
    fun accessToken(ctx: Context): String? =
        prefs(ctx).getString(KEY_ACCESS, null)

    fun refreshToken(ctx: Context): String? =
        prefs(ctx).getString(KEY_REFRESH, null)

    /** The JWT "sub" claim — your user-id for per-user chat storage. */
    fun username(ctx: Context): String? =
        prefs(ctx).getString(KEY_USER, null)

    fun isLoggedIn(ctx: Context): Boolean =
        accessToken(ctx) != null

    // ── Helpers ──────────────────────────────────────────────────────
    /**
     * Decode the JWT payload (middle segment) without verification
     * just to read the "sub" claim locally.
     */
    private fun extractUsername(jwt: String): String? {
        return try {
            val parts = jwt.split(".")
            if (parts.size != 3) return null
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP))
            JSONObject(payload).optString("sub", null)
        } catch (_: Exception) {
            null
        }
    }
}