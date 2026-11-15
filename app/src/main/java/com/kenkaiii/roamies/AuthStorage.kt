package com.kenkaiii.roamies

import android.content.Context
import android.content.SharedPreferences

enum class RoamieCharacter(val id: String, val displayName: String) {
    JARVIS("jarvis", "JARVIS (3D Glowing LED Sphere)"),
    ROAMIE("classic", "Roamie (Classic Pixel Creature)"),
    ANIME("anime", "Anime Girl (Swinging Twintails)"),
    SARAH("sarah", "Sarah (Long Flowing Hair)"),
    ADRIAN("adrian", "Adrian (Bouncy Quiff)")
}

enum class RoamieSize(val id: String, val displayName: String) {
    DEFAULT("default", "Default"),
    SMALL("small", "Small"),
    CURSOR("cursor", "Cursor / Reticle")
}

class AuthStorage(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("roamies_settings", Context.MODE_PRIVATE)

    var character: RoamieCharacter
        get() {
            val name = prefs.getString("character", RoamieCharacter.JARVIS.name)
            return try { RoamieCharacter.valueOf(name ?: RoamieCharacter.JARVIS.name) } catch (e: Exception) { RoamieCharacter.JARVIS }
        }
        set(value) = prefs.edit().putString("character", value.name).apply()

    var size: RoamieSize
        get() {
            val name = prefs.getString("size", RoamieSize.DEFAULT.name)
            return try { RoamieSize.valueOf(name ?: RoamieSize.DEFAULT.name) } catch (e: Exception) { RoamieSize.DEFAULT }
        }
        set(value) = prefs.edit().putString("size", value.name).apply()

    var provider: String
        get() = prefs.getString("provider", "gemini") ?: "gemini"
        set(value) = prefs.edit().putString("provider", value).apply()

    var modelId: String
        get() = prefs.getString("modelId", "gemini-1.5-flash") ?: "gemini-1.5-flash"
        set(value) = prefs.edit().putString("modelId", value).apply()

    var model: String
        get() = modelId
        set(value) { modelId = value }

    var endpoint: String
        get() = prefs.getString("endpoint", "") ?: ""
        set(value) = prefs.edit().putString("endpoint", value).apply()

    var apiKey: String
        get() = prefs.getString("apiKey", "") ?: ""
        set(value) = prefs.edit().putString("apiKey", value).apply()

    var fishAudioKey: String
        get() = prefs.getString("fishAudioKey", "") ?: ""
        set(value) = prefs.edit().putString("fishAudioKey", value).apply()

    var handsEnabled: Boolean
        get() = prefs.getBoolean("handsEnabled", true)
        set(value) = prefs.edit().putBoolean("handsEnabled", value).apply()

    var isOverlayActive: Boolean
        get() = prefs.getBoolean("isOverlayActive", false)
        set(value) = prefs.edit().putBoolean("isOverlayActive", value).apply()
}
