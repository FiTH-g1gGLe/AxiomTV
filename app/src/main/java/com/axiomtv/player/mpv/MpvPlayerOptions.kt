package com.axiomtv.player.mpv

import java.io.File

/**
 * Options for configuring [MpvPlayer]
 */
data class MpvPlayerOptions(
    val enableHardwareDecoding: Boolean,
    val useGpuNext: Boolean,
    val tlsCertFile: File? = null,
    /** Sent to the provider with every request (matches ExoPlayer's) */
    val userAgent: String = "AxiomTV",
)
