package com.github.damontecres.wholphin.axiom.ui

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** The artwork that sets the mood behind Home, Movies, Series, Search and Settings. */
object Backdrop {
    var image by mutableStateOf<String?>(null)
}

/** Softly blurred artwork of whatever is selected, under a navy scrim. */
@Composable
fun AmbientBackdrop(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(AxiomColors.Background)) {
        Crossfade(targetState = Backdrop.image, animationSpec = tween(700), label = "ambient") { image ->
            if (!image.isNullOrBlank()) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            // Older boxes can't blur: show the art fainter instead
                            .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(60.dp).alpha(0.55f) else Modifier.alpha(0.22f)),
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0x660A1020), Color(0xB307080A), Color(0xF207080A)))),
        )
    }
}
