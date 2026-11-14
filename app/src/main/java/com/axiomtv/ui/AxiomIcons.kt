package com.axiomtv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** AxiomTV's own line icons (the bundled icon set has no TV/film icons). */
object AxiomIcons {
    private fun icon(
        name: String,
        block: PathBuilder.() -> Unit,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            ).build()

    val LiveTv: ImageVector =
        icon("LiveTv") {
            moveTo(4f, 7f)
            lineTo(20f, 7f)
            lineTo(20f, 19f)
            lineTo(4f, 19f)
            close()
            moveTo(8.5f, 2.5f)
            lineTo(12f, 6.5f)
            lineTo(15.5f, 2.5f)
        }

    val Movie: ImageVector =
        icon("Movie") {
            moveTo(3.5f, 4.5f)
            lineTo(20.5f, 4.5f)
            lineTo(20.5f, 19.5f)
            lineTo(3.5f, 19.5f)
            close()
            moveTo(7.5f, 4.5f)
            lineTo(7.5f, 19.5f)
            moveTo(16.5f, 4.5f)
            lineTo(16.5f, 19.5f)
            moveTo(3.5f, 9.5f)
            lineTo(7.5f, 9.5f)
            moveTo(3.5f, 14.5f)
            lineTo(7.5f, 14.5f)
            moveTo(16.5f, 9.5f)
            lineTo(20.5f, 9.5f)
            moveTo(16.5f, 14.5f)
            lineTo(20.5f, 14.5f)
        }

    val Series: ImageVector =
        icon("Series") {
            moveTo(3.5f, 8.5f)
            lineTo(16.5f, 8.5f)
            lineTo(16.5f, 20f)
            lineTo(3.5f, 20f)
            close()
            moveTo(7f, 4.5f)
            lineTo(20.5f, 4.5f)
            lineTo(20.5f, 16f)
        }

    val Refresh: ImageVector =
        icon("Refresh") {
            moveTo(20f, 12f)
            arcTo(8f, 8f, 0f, true, true, 17.6f, 6.3f)
            moveTo(20f, 3.5f)
            lineTo(20f, 8f)
            lineTo(15.5f, 8f)
        }
}
