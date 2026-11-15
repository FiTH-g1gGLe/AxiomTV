package com.axiomtv.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axiomtv.LicenceInfo
import com.axiomtv.LicenceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LicenceViewModel
    @Inject
    constructor(
        val manager: LicenceManager,
    ) : ViewModel() {
        var checking by mutableStateOf(false)
            private set

        fun refresh(onDone: ((LicenceInfo) -> Unit)? = null) {
            if (checking) return
            checking = true
            viewModelScope.launch {
                val result = manager.refresh()
                checking = false
                onDone?.invoke(result)
            }
        }
    }

private object PaywallColors {
    val Navy = Color(0xFF0A1024)
    val NavyLight = Color(0xFF1D2B55)
    val Gold = Color(0xFFD9B45A)
    val OnGold = Color(0xFF1A1406)
    val Text = Color(0xFFE8ECF6)
    val Muted = Color(0xFFB9C2DA)
    val Faint = Color(0xFF8F9ABB)
    val Glass = Color(0x8C142046)
    val GlassEdge = Color(0x1AFFFFFF)
    val Tile = Color(0x99142046)
    val Green = Color(0xFF6FDC8C)
    val Amber = Color(0xFFFFB454)
}

/** The unlock screen: device code, QR to pay, and a check that unlocks by itself once paid. */
@Composable
fun PaywallScreen(
    licence: LicenceViewModel,
    onExit: () -> Unit,
) {
    val manager = licence.manager
    val info = manager.info
    var message by remember { mutableStateOf<String?>(null) }
    val checkFocus = remember { FocusRequester() }

    // Checks every few seconds, so the screen goes away by itself after payment
    LaunchedEffect(Unit) {
        runCatching { checkFocus.requestFocus() }
        while (true) {
            delay(5_000)
            if (!manager.previewing) licence.refresh()
        }
    }

    // Laid out on a fixed design canvas, then scaled to fit the screen (no scrolling on any TV or phone)
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(PaywallColors.Navy)
                .background(Brush.verticalGradient(listOf(PaywallColors.NavyLight, Color.Transparent), endY = 900f)),
        contentAlignment = Alignment.Center,
    ) {
        val wide = maxWidth > maxHeight
        val designWidth = if (wide) 1280.dp else 420.dp
        val designHeight = if (wide) 720.dp else 900.dp
        val scale = minOf(maxWidth / designWidth, maxHeight / designHeight, 1.6f)
        Box(
            modifier =
                Modifier
                    .requiredSize(designWidth, designHeight)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
        ) {

        info.price?.let { price ->
            Text(
                price,
                color = PaywallColors.Gold,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 28.dp, end = 36.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x1FD9B45A))
                        .border(1.dp, Color(0x73D9B45A), RoundedCornerShape(14.dp))
                        .padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (wide) 72.dp else 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Logo, name and the offer
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.height(if (wide) 0.dp else 24.dp))
                Image(
                    painter = painterResource(com.axiomtv.R.drawable.axiom_logo),
                    contentDescription = "AxiomTV logo",
                    modifier = Modifier.height(if (wide) 96.dp else 80.dp),
                )
                Text("AXIOMTV", fontFamily = AxiomBrandFont, fontSize = if (wide) 30.sp else 24.sp, letterSpacing = 9.sp, color = PaywallColors.Gold)
                Text(
                    buildAnnotatedString {
                        if (info.price != null) {
                            append("One payment of ")
                            withStyle(SpanStyle(color = PaywallColors.Gold, fontWeight = FontWeight.Bold)) { append(info.price ?: "") }
                            append(" unlocks")
                        } else {
                            append("One payment unlocks")
                        }
                        append(" this device for life, so you can watch your provider's live TV, movies and TV series.")
                    },
                    color = PaywallColors.Muted,
                    fontSize = if (wide) 18.sp else 16.sp,
                    lineHeight = if (wide) 27.sp else 23.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 760.dp),
                )
            }

            // Device code
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(vertical = 18.dp),
            ) {
                Text("YOUR DEVICE CODE", color = PaywallColors.Faint, fontSize = 14.sp, letterSpacing = 3.sp)
                val code = info.code
                if (code == null) {
                    Text(
                        if (info.offline) "Can't reach the AxiomTV server" else "Getting your code…",
                        color = PaywallColors.Text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    CodeTiles(code, tileWidth = if (wide) 70.dp else 30.dp, tileHeight = if (wide) 86.dp else 46.dp, fontSize = if (wide) 56.sp else 28.sp)
                }
            }

            // QR, steps, status and buttons
            val panel =
                Modifier
                    .widthIn(max = 1080.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(PaywallColors.Glass)
                    .border(1.dp, PaywallColors.GlassEdge, RoundedCornerShape(22.dp))
                    .padding(horizontal = 28.dp, vertical = 20.dp)
            val steps =
                if (info.paymentsOpen) {
                    listOf("Scan with your phone", "Pay securely", "Unlocks after payment")
                } else {
                    listOf("Scan with your phone", "Send your code to the seller", "Unlocks once approved")
                }
            val status: @Composable () -> Unit = {
                StatusLine(offline = info.offline, previewing = manager.previewing, message = message)
            }
            val buttons: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PaywallButton(
                        text = if (licence.checking) "Checking…" else "I've paid — check now",
                        primary = true,
                        modifier = Modifier.focusRequester(checkFocus),
                    ) {
                        message = null
                        licence.refresh { result ->
                            if (manager.locked && !manager.previewing) {
                                message = if (result.offline) "Couldn't reach the server — check your internet" else "Not unlocked yet"
                            }
                        }
                    }
                    PaywallButton(text = if (manager.previewing) "Close preview" else "Exit", primary = false, onClick = onExit)
                }
            }

            if (wide) {
                Row(modifier = panel, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    QrBlock(info.payUrl)
                    StepList(steps)
                    Box(Modifier.width(1.dp).height(120.dp).background(PaywallColors.GlassEdge))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        status()
                        buttons()
                    }
                }
            } else {
                Column(modifier = panel, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    QrBlock(info.payUrl)
                    StepList(steps)
                    status()
                    buttons()
                }
            }
        }
        }
    }
}

@Composable
private fun CodeTiles(
    code: String,
    tileWidth: androidx.compose.ui.unit.Dp,
    tileHeight: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(tileWidth / 7)) {
        code.forEach { ch ->
            if (ch == '-') {
                Box(Modifier.width(tileWidth / 2).height(tileHeight), contentAlignment = Alignment.Center) {
                    Text("-", color = PaywallColors.Gold, fontSize = fontSize, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            } else {
                Box(
                    modifier =
                        Modifier
                            .width(tileWidth)
                            .height(tileHeight)
                            .clip(RoundedCornerShape(14.dp))
                            .background(PaywallColors.Tile)
                            .border(1.dp, PaywallColors.GlassEdge, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ch.toString(), color = PaywallColors.Gold, fontSize = fontSize, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun QrBlock(url: String?) {
    val qr = remember(url) { url?.let { payQr(it, 400) } }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier.size(140.dp).clip(RoundedCornerShape(12.dp)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (qr != null) {
                Image(bitmap = qr, contentDescription = "QR code to pay for this device", modifier = Modifier.fillMaxSize())
            } else {
                Text("…", color = PaywallColors.Navy, fontSize = 28.sp)
            }
        }
        Text("Scan with your phone", color = PaywallColors.Muted, fontSize = 14.sp)
    }
}

@Composable
private fun StepList(steps: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        steps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(PaywallColors.Gold), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", color = PaywallColors.OnGold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Text(step, color = PaywallColors.Text, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun StatusLine(
    offline: Boolean,
    previewing: Boolean,
    message: String?,
) {
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "blink",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(12.dp)
                .alpha(blink)
                .clip(CircleShape)
                .background(if (offline) PaywallColors.Amber else PaywallColors.Green),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                when {
                    previewing -> "Preview"
                    offline -> "Can't reach the server"
                    else -> "Waiting for payment"
                },
                color = PaywallColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                message ?: if (previewing) "This is how locked devices see AxiomTV" else "This screen unlocks by itself once you've paid",
                color = if (message != null) PaywallColors.Gold else PaywallColors.Muted,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun PaywallButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val background =
        when {
            primary && focused -> PaywallColors.Gold
            primary -> Color(0xFFB8964A)
            focused -> Color(0x33FFFFFF)
            else -> Color(0x0FFFFFFF)
        }
    Box(
        modifier =
            modifier
                .then(if (focused) Modifier.shadow(18.dp, shape, ambientColor = PaywallColors.Gold, spotColor = PaywallColors.Gold) else Modifier)
                .clip(shape)
                .background(background)
                .border(2.dp, if (focused) (if (primary) Color(0xFFF3D98E) else PaywallColors.Gold) else Color(0x24FFFFFF), shape)
                .onFocusChanged { focused = it.isFocused }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (primary) PaywallColors.OnGold else PaywallColors.Text,
            fontSize = 17.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

private fun payQr(
    text: String,
    size: Int,
): ImageBitmap? =
    runCatching {
        val matrix =
            com.google.zxing.qrcode.QRCodeWriter().encode(
                text,
                com.google.zxing.BarcodeFormat.QR_CODE,
                size,
                size,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 2),
            )
        val dark = 0xFF0A1024.toInt()
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) dark else 0xFFFFFFFF.toInt() }
        android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()
