package com.github.damontecres.wholphin.axiom

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.damontecres.wholphin.axiom.player.Engine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** AxiomTV's own settings, kept on the device. Observable from Compose. */
@Singleton
class AppSettings
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences("axiomtv_settings", Context.MODE_PRIVATE)

        /** First engine tried for live channels (the other is the automatic backup). */
        var liveEngine by mutableStateOf(engine("live_engine", Engine.EXO))
            private set

        /** First engine tried for films, episodes and catch-up. */
        var vodEngine by mutableStateOf(engine("vod_engine", Engine.MPV))
            private set

        /** "home" or "live" */
        var startScreen by mutableStateOf(prefs.getString("start_screen", "home") ?: "home")
            private set

        /** Automatically switch engines when one fails. */
        var autoFallback by mutableStateOf(prefs.getBoolean("auto_fallback", true))
            private set

        /** Seconds an engine gets to start playing before switching to the other one. */
        var switchDelaySeconds by mutableStateOf(prefs.getInt("switch_delay_s", 4))
            private set

        /** Layout of the Movies and Series pages (LibraryStyle name) */
        var movieStyle by mutableStateOf(prefs.getString("movie_style", "POSTERS") ?: "POSTERS")
            private set
        var seriesStyle by mutableStateOf(prefs.getString("series_style", "POSTERS") ?: "POSTERS")
            private set

        fun updateMovieStyle(value: String) {
            movieStyle = value
            prefs.edit().putString("movie_style", value).apply()
        }

        fun updateSeriesStyle(value: String) {
            seriesStyle = value
            prefs.edit().putString("series_style", value).apply()
        }

        fun updateSwitchDelay(value: Int) {
            switchDelaySeconds = value
            prefs.edit().putInt("switch_delay_s", value).apply()
        }

        fun updateLiveEngine(value: Engine) {
            liveEngine = value
            prefs.edit().putString("live_engine", value.name).apply()
        }

        fun updateVodEngine(value: Engine) {
            vodEngine = value
            prefs.edit().putString("vod_engine", value.name).apply()
        }

        fun updateStartScreen(value: String) {
            startScreen = value
            prefs.edit().putString("start_screen", value).apply()
        }

        fun updateAutoFallback(value: Boolean) {
            autoFallback = value
            prefs.edit().putBoolean("auto_fallback", value).apply()
        }

        private fun engine(
            key: String,
            default: Engine,
        ): Engine = Engine.entries.firstOrNull { it.name == prefs.getString(key, null) } ?: default
    }
