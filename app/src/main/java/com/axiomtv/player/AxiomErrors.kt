package com.axiomtv.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/** Whose problem an error is, which decides what Help tells people to do. */
enum class ErrorSource { PROVIDER, INTERNET, AXIOMTV }

/** A plain-English error: code, title, one-line explanation and what to try. */
data class ErrorInfo(
    val code: Int,
    val title: String,
    val message: String,
    val tips: List<String>,
    val source: ErrorSource,
    /** Extra line under the title, e.g. the channel or the expiry date */
    val detail: String? = null,
    /** The provider refused the stream: ask the provider's account page why (expired, too many screens…) */
    val checkAccount: Boolean = false,
) {
    val helpUrl: String get() = "https://axiomtv.co.uk/help/$code"
}

/** AxiomTV's error codes. First digit: 1 stream, 2 provider account, 3 internet, 4 TV guide, 5 AxiomTV. Keep in step with website/src/help.js. */
object AxiomErrors {
    val CHANNEL_OFFLINE =
        ErrorInfo(
            1001,
            "Channel offline",
            "Your provider isn't sending this channel right now. It's not a problem with your box or AxiomTV.",
            listOf("Pick another channel, then come back to this one in a few minutes.", "If it stays offline for hours, let your provider know."),
            ErrorSource.PROVIDER,
        )
    val NOT_FOUND =
        ErrorInfo(
            1002,
            "Not available any more",
            "Your provider has removed or moved this, so it can't be played.",
            listOf("Go back and pick it again from the list.", "If it's still missing, ask your provider."),
            ErrorSource.PROVIDER,
        )
    val TOO_SLOW =
        ErrorInfo(
            1003,
            "Took too long to start",
            "Your provider's server didn't send the video in time.",
            listOf("Press Try again.", "Try another channel or film to see if it's just this one."),
            ErrorSource.PROVIDER,
        )
    val TOO_MANY_SCREENS =
        ErrorInfo(
            1004,
            "Too many screens",
            "Your provider account is already being used on its maximum number of screens.",
            listOf("Stop watching on another device, then press Try again.", "Ask your provider if you need more screens."),
            ErrorSource.PROVIDER,
        )
    val FORMAT =
        ErrorInfo(
            1005,
            "Video format not supported",
            "This box can't play the way this video has been encoded.",
            listOf("Press Try again: AxiomTV may use its other player.", "Try another version (SD or HD) if your provider has one."),
            ErrorSource.AXIOMTV,
        )
    val WRONG_LOGIN =
        ErrorInfo(
            2001,
            "Login not accepted",
            "Your provider didn't accept your username or password.",
            listOf("Check your login details with your provider.", "Then sign out in Settings and sign in again."),
            ErrorSource.PROVIDER,
        )
    val EXPIRED =
        ErrorInfo(
            2002,
            "Your provider account has expired",
            "Your subscription with your TV provider has ended, so there's nothing to play. AxiomTV itself is working fine.",
            listOf("Renew with your provider, then press Try again.", "Got a new login? Sign out in Settings and enter it."),
            ErrorSource.PROVIDER,
        )
    val SUSPENDED =
        ErrorInfo(
            2003,
            "Account suspended",
            "Your provider has suspended or disabled your account.",
            listOf("Contact your provider to find out why."),
            ErrorSource.PROVIDER,
        )
    val SERVER_DOWN =
        ErrorInfo(
            2004,
            "Provider's server isn't answering",
            "Your provider's server is down or very slow right now. It's not your box.",
            listOf("Wait a few minutes and press Try again.", "If it lasts, check with your provider."),
            ErrorSource.PROVIDER,
        )
    val REFUSED =
        ErrorInfo(
            2005,
            "Provider refused the connection",
            "Your provider's server refused to send this. That often happens with a VPN, or when an account is restricted.",
            listOf("Turn off any VPN and press Try again.", "If it keeps happening, contact your provider."),
            ErrorSource.PROVIDER,
        )
    val NO_INTERNET =
        ErrorInfo(
            3001,
            "No internet connection",
            "This box isn't connected to the internet.",
            listOf("Check your Wi-Fi or network cable.", "If other devices are offline too, restart your router."),
            ErrorSource.INTERNET,
        )

    /** Picks the right error for a stream that couldn't be played. */
    fun forPlayback(
        context: Context,
        error: PlaybackException?,
        reason: String,
        isLive: Boolean,
    ): ErrorInfo {
        if (error == null) {
            return when {
                reason.contains("format") -> FORMAT
                !isOnline(context) -> NO_INTERNET
                else -> TOO_SLOW
            }
        }
        val http =
            generateSequence(error as Throwable?) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()
                ?.responseCode
        return when {
            http == 404 || http == 410 -> if (isLive) CHANNEL_OFFLINE else NOT_FOUND
            http != null && http in 400..499 -> REFUSED.copy(checkAccount = true)
            http != null && http >= 500 -> if (isLive) CHANNEL_OFFLINE else SERVER_DOWN
            error.errorCode in FORMAT_CODES -> FORMAT
            error.errorCode in BROKEN_STREAM_CODES -> if (isLive) CHANNEL_OFFLINE else FORMAT
            error.errorCode in NETWORK_CODES -> if (!isOnline(context)) NO_INTERNET else SERVER_DOWN
            !isOnline(context) -> NO_INTERNET
            isLive -> CHANNEL_OFFLINE
            else -> TOO_SLOW
        }
    }

    fun isOnline(context: Context): Boolean =
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)

    private val FORMAT_CODES =
        setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        )
    private val BROKEN_STREAM_CODES =
        setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )
    private val NETWORK_CODES =
        setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
        )
}
