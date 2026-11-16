package com.axiomtv

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import timber.log.Timber
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a move-to-another-box request. */
sealed interface MoveResult {
    data object Done : MoveResult

    data class Code(
        val code: String,
        val expiresAtMs: Long,
    ) : MoveResult

    data class Failed(
        val message: String,
    ) : MoveResult
}

/** What the paywall needs to know about this device. */
data class LicenceInfo(
    /** Code shown on screen, e.g. 7K3P-9QXD (null until the server has been reached once) */
    val code: String? = null,
    /** unpaid | active | revoked */
    val status: String = "unknown",
    val payUrl: String? = null,
    val price: String? = null,
    val paymentsOpen: Boolean = false,
    /** Whether the paywall is switched on in the admin page (null = never heard from the server) */
    val enforce: Boolean? = null,
    /** The last check couldn't reach the server */
    val offline: Boolean = false,
    /** End of this box's free trial (epoch ms), null when there's no trial running */
    val trialEndsMs: Long? = null,
) {
    val inTrial: Boolean get() = trialEndsMs != null && System.currentTimeMillis() < trialEndsMs

    /** Whole days left, counting part of a day as a day */
    val trialDaysLeft: Int get() = trialEndsMs?.let { (((it - System.currentTimeMillis()) + 86_399_999) / 86_400_000).toInt().coerceAtLeast(0) } ?: 0
}

/**
 * Unlocks AxiomTV per device through the licence server (licence-server/ in this repo).
 * The server signs a licence for paid devices; it's checked here with the server's public key,
 * so it keeps working offline for a while and can't be faked by a different server.
 */
@Singleton
class LicenceManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:StandardOkHttpClient okHttpClient: OkHttpClient,
    ) {
        private val prefs = context.getSharedPreferences("axiomtv_licence", Context.MODE_PRIVATE)
        private val client = okHttpClient.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
        private val json = Json { ignoreUnknownKeys = true }

        var info by mutableStateOf(loadInfo())
            private set

        /** Shows the paywall from Settings without locking anything. */
        var previewing by mutableStateOf(false)

        /** True while the app must show the paywall. */
        val locked: Boolean get() = previewing || (info.enforce != false && !info.inTrial && !hasValidLicence(allowGrace = info.offline))

        /** True until the first answer from the server (only on a fresh install). */
        val undecided: Boolean get() = info.enforce == null && !hasValidLicence(allowGrace = true)

        val unlocked: Boolean get() = hasValidLicence(allowGrace = true)

        private val deviceId: String by lazy { readDeviceId() }
        private val deviceHash: String by lazy { sha256Hex(DEVICE_SALT + deviceId) }

        /** Asks the server about this device. Safe to call often. */
        suspend fun refresh(): LicenceInfo =
            withContext(Dispatchers.IO) {
                val body =
                    buildString {
                        append("{\"device_id\":").append(jsonString(deviceId)).append(',')
                        append("\"model\":").append(jsonString(android.os.Build.MODEL ?: "")).append(',')
                        append("\"app_version\":").append(jsonString(BuildConfig.VERSION_NAME)).append('}')
                    }
                val request =
                    Request
                        .Builder()
                        .url("$SERVER/v1/check")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                val result =
                    runCatching {
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) error("HTTP ${response.code}")
                            json.parseToJsonElement(response.body.string()).jsonObject
                        }
                    }
                val reply = result.getOrNull()
                val updated =
                    if (reply == null) {
                        Timber.w(result.exceptionOrNull(), "Licence check failed")
                        info.copy(offline = true)
                    } else {
                        val status = reply.str("status") ?: "unpaid"
                        val licence = reply.str("licence")
                        if (status == "active" && licence != null && verify(licence) != null) {
                            prefs.edit().putString(KEY_LICENCE, licence).apply()
                        } else if (status != "active") {
                            prefs.edit().remove(KEY_LICENCE).apply()
                        }
                        LicenceInfo(
                            code = reply.str("code"),
                            status = status,
                            payUrl = reply.str("pay_url"),
                            price = reply.str("price"),
                            paymentsOpen = reply["payments_open"]?.jsonPrimitive?.booleanOrNull ?: false,
                            enforce = reply["enforce"]?.jsonPrimitive?.booleanOrNull ?: false,
                            offline = false,
                            trialEndsMs = reply["trial_ends"]?.jsonPrimitive?.longOrNull,
                        ).also(::saveInfo)
                    }
                withContext(Dispatchers.Main) { info = updated }
                updated
            }

        /** Old box: gets a 6-digit code to type on the new box. */
        suspend fun startTransfer(): MoveResult {
            val (reply, failure) = post("/v1/transfer/start", emptyMap())
            if (reply == null) return MoveResult.Failed(failure)
            val code = reply.str("transfer_code") ?: return MoveResult.Failed(errorText(reply))
            return MoveResult.Code(code, reply["expires_at"]?.jsonPrimitive?.longOrNull ?: (System.currentTimeMillis() + 1_800_000))
        }

        /** Settings > Licence > Link Discord: a 6-digit code to type after /verify in the AxiomTV Discord. */
        suspend fun discordLinkCode(): MoveResult {
            val (reply, failure) = post("/v1/discord/link", emptyMap())
            if (reply == null) return MoveResult.Failed(failure)
            val code = reply.str("link_code") ?: return MoveResult.Failed("Couldn't get a code. Open AxiomTV once more and try again.")
            return MoveResult.Code(code, reply["expires_at"]?.jsonPrimitive?.longOrNull ?: (System.currentTimeMillis() + 600_000))
        }

        /** New box: the 6-digit code from the old box. */
        suspend fun claimTransfer(transferCode: String): MoveResult = finishMove(post("/v1/transfer/claim", mapOf("transfer_code" to transferCode)))

        /** New box: the email and order number from the receipt. */
        suspend fun claimByReceipt(
            email: String,
            order: String,
        ): MoveResult = finishMove(post("/v1/transfer/receipt", mapOf("email" to email, "order" to order)))

        private suspend fun finishMove(result: Pair<JsonObject?, String>): MoveResult {
            val (reply, failure) = result
            if (reply == null) return MoveResult.Failed(failure)
            if (reply["ok"]?.jsonPrimitive?.booleanOrNull != true) return MoveResult.Failed(errorText(reply))
            refresh()
            return MoveResult.Done
        }

        private fun errorText(reply: JsonObject): String =
            when (reply.str("error")) {
                "invalid_code" -> "That code isn't right or has run out. Make a new one on the old device."
                "order_not_found" -> "We couldn't find that order. Check the email and the order number on your receipt."
                "same_box" -> "That's this device. Make the code on your old device and type it here."
                "not_unlocked" -> "This device isn't unlocked, so there's nothing to move."
                "too_many_attempts" -> "Too many tries. Wait 10 minutes and try again."
                "move_limit" -> {
                    val next = reply["next_allowed"]?.jsonPrimitive?.longOrNull
                    val date = next?.let { java.text.SimpleDateFormat("d MMMM", java.util.Locale.UK).format(java.util.Date(it)) }
                    "This licence was moved recently. You can move it again on ${date ?: "a later date"}, or message the seller."
                }
                else -> "Something went wrong. Try again in a minute."
            }

        /** Sends this device's ID plus [fields]; returns the JSON reply (also for error codes) or a failure text. */
        private suspend fun post(
            path: String,
            fields: Map<String, String>,
        ): Pair<JsonObject?, String> =
            withContext(Dispatchers.IO) {
                val body =
                    kotlinx.serialization.json
                        .JsonObject((fields + ("device_id" to deviceId)).mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) })
                        .toString()
                val request =
                    Request
                        .Builder()
                        .url(SERVER + path)
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                runCatching {
                    client.newCall(request).execute().use { response ->
                        json.parseToJsonElement(response.body.string()).jsonObject
                    }
                }.fold(
                    onSuccess = { it to "" },
                    onFailure = {
                        Timber.w(it, "Licence request %s failed", path)
                        null to "Couldn't reach the AxiomTV server. Check your internet connection."
                    },
                )
            }

        private fun hasValidLicence(allowGrace: Boolean): Boolean {
            val token = prefs.getString(KEY_LICENCE, null) ?: return false
            val exp = verify(token) ?: return false
            val now = System.currentTimeMillis() / 1000
            return now < exp || (allowGrace && now < exp + OFFLINE_GRACE_S)
        }

        /** Returns the licence's expiry (seconds) if it is genuine and for this device, else null. */
        private fun verify(token: String): Long? =
            runCatching {
                val parts = token.split('.')
                if (parts.size != 2) return null
                val signature = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                val signer = Ed25519Signer()
                signer.init(false, Ed25519PublicKeyParameters(Base64.decode(PUBLIC_KEY, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), 0))
                val signed = parts[0].toByteArray(Charsets.US_ASCII)
                signer.update(signed, 0, signed.size)
                if (!signer.verifySignature(signature)) return null
                val payload =
                    json
                        .parseToJsonElement(
                            Base64.decode(parts[0], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).decodeToString(),
                        ).jsonObject
                if (payload.str("app") != "axiomtv" || payload.str("s") != "active" || payload.str("dh") != deviceHash) return null
                payload["exp"]?.jsonPrimitive?.longOrNull
            }.getOrNull()

        @SuppressLint("HardwareIds")
        private fun readDeviceId(): String {
            val androidId = runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()
            if (!androidId.isNullOrBlank() && androidId != BROKEN_ANDROID_ID && androidId.length >= 8) return androidId
            // Some boxes report no ID (or the same one on every box): fall back to one made for this install
            return prefs.getString(KEY_FALLBACK_ID, null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(KEY_FALLBACK_ID, it).apply()
            }
        }

        private fun loadInfo(): LicenceInfo =
            LicenceInfo(
                code = prefs.getString("code", null),
                status = prefs.getString("status", "unknown") ?: "unknown",
                payUrl = prefs.getString("pay_url", null),
                price = prefs.getString("price", null),
                paymentsOpen = prefs.getBoolean("payments_open", false),
                enforce = if (prefs.contains("enforce")) prefs.getBoolean("enforce", false) else null,
                trialEndsMs = prefs.getLong("trial_ends", 0L).takeIf { it > 0 },
            )

        private fun saveInfo(info: LicenceInfo) {
            prefs
                .edit()
                .putString("code", info.code)
                .putString("status", info.status)
                .putString("pay_url", info.payUrl)
                .putString("price", info.price)
                .putBoolean("payments_open", info.paymentsOpen)
                .putBoolean("enforce", info.enforce == true)
                .putLong("trial_ends", info.trialEndsMs ?: 0L)
                .apply()
        }

        private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

        private fun jsonString(text: String) = kotlinx.serialization.json.JsonPrimitive(text).toString()

        private fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        companion object {
            const val SERVER = "https://axiomtv-licence.dj-giggle.workers.dev"

            /** From licence-server/deployed.json */
            private const val PUBLIC_KEY = "KhOmSSLxg3s2LnDfvGIo5LP_Sgjm7qao9BOu73OnziM"
            private const val DEVICE_SALT = "axiomtv:"
            private const val KEY_LICENCE = "licence"
            private const val KEY_FALLBACK_ID = "fallback_device_id"
            private const val BROKEN_ANDROID_ID = "9774d56d682e549c"

            /** A paid box keeps working this long past its licence date when it can't reach the server. */
            private const val OFFLINE_GRACE_S = 30L * 86400
        }
    }
