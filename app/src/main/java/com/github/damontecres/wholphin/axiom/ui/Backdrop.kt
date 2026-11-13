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
import coil3.request.allowHardware
import coil3.toBitmap

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

/** The main colour of a piece of artwork (for glows that match the film). Gold until it's worked out. */
@androidx.compose.runtime.Composable
fun rememberArtworkColour(url: String?): Color {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colour by androidx.compose.runtime.produceState(AxiomColors.Gold, url) {
        if (url.isNullOrBlank()) return@produceState
        value =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    val request =
                        coil3.request.ImageRequest
                            .Builder(context)
                            .data(url)
                            .size(96)
                            .allowHardware(false)
                            .build()
                    val result = coil3.SingletonImageLoader.get(context).execute(request)
                    val bitmap = (result as? coil3.request.SuccessResult)?.image?.toBitmap() ?: return@runCatching null
                    val palette = androidx.palette.graphics.Palette.from(bitmap).generate()
                    val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch
                    swatch?.rgb?.let { Color(it) }
                }.getOrNull()
            } ?: AxiomColors.Gold
    }
    return colour
}
