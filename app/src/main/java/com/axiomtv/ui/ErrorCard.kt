package com.axiomtv.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.axiomtv.player.ErrorInfo
import com.axiomtv.player.ErrorSource

/** A button on the error card. */
data class ErrorAction(
    val label: String,
    val onClick: () -> Unit,
)

/** Which error-card button is selected, and whether Help is open. The player screens own the remote keys. */
class ErrorCardState {
    var selected by mutableIntStateOf(0)
    var showHelp by mutableStateOf(false)

    /** Left/Right move between buttons, OK presses one. Returns false for keys the card doesn't use. */
    fun handleKey(
        key: Key,
        actions: List<ErrorAction>,
    ): Boolean {
        if (actions.isEmpty()) return false
        selected = selected.coerceIn(0, actions.lastIndex)
        return when (key) {
            Key.DirectionLeft -> {
                selected = (selected - 1).coerceAtLeast(0)
                true
            }
            Key.DirectionRight -> {
                selected = (selected + 1).coerceAtMost(actions.lastIndex)
                true
            }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                actions[selected].onClick()
                true
            }
            else -> false
        }
    }
}

private object ErrorColors {
    val Card = Color(0x9E142046)
    val Edge = Color(0x1AFFFFFF)
    val Inner = Color(0x8C0A1024)
    val Gold = Color(0xFFD9B45A)
    val GoldLight = Color(0xFFF3D98E)
    val OnGold = Color(0xFF1A1406)
    val Text = Color(0xFFE8ECF6)
    val Body = Color(0xFFD4DAEA)
    val Muted = Color(0xFFB9C2DA)
    val Faint = Color(0xFF8F9ABB)
    val Amber = Color(0xFFFFB454)
    val Red = Color(0xFFFF7B8A)
}

/** The frosted error card shown over the picture (see the "AxiomTV error screens" designs). */
@Composable
fun ErrorCard(
    info: ErrorInfo,
    actions: List<ErrorAction>,
    state: ErrorCardState,
    modifier: Modifier = Modifier,
) {
    val accent = if (info.code / 1000 == 2) ErrorColors.Red else ErrorColors.Amber
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier =
            modifier
                .widthIn(max = 700.dp)
                .shadow(30.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(ErrorColors.Card)
                .border(1.dp, ErrorColors.Edge, shape)
                .padding(start = 36.dp, end = 36.dp, top = 30.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("!", color = accent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(info.title, color = ErrorColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                info.detail?.let { Text(it, color = ErrorColors.Muted, fontSize = 15.sp) }
            }
        }
        Text(info.message, color = ErrorColors.Body, fontSize = 17.sp, lineHeight = 25.sp)
        if (info.tips.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ErrorColors.Inner).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("What you can try", color = ErrorColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                info.tips.forEach { Text(it, color = ErrorColors.Muted, fontSize = 15.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            actions.forEachIndexed { index, action ->
                val selected = index == state.selected.coerceIn(0, actions.lastIndex)
                val bShape = RoundedCornerShape(12.dp)
                Box(
                    modifier =
                        Modifier
                            .then(if (selected) Modifier.shadow(16.dp, bShape, ambientColor = ErrorColors.Gold, spotColor = ErrorColors.Gold) else Modifier)
                            .clip(bShape)
                            .background(if (selected) ErrorColors.Gold else Color(0x0FFFFFFF))
                            .border(2.dp, if (selected) ErrorColors.GoldLight else Color(0x24FFFFFF), bShape)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                state.selected = index
                                action.onClick()
                            }.padding(horizontal = 22.dp, vertical = 12.dp),
                ) {
                    Text(
                        action.label,
                        color = if (selected) ErrorColors.OnGold else ErrorColors.Text,
                        fontSize = 16.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x12FFFFFF)))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Error ${info.code}", color = ErrorColors.Faint, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text(footerText(info, LocalContext.current), color = ErrorColors.Faint, fontSize = 13.sp)
        }
    }
    if (state.showHelp) ErrorHelpDialog(info) { state.showHelp = false }
}

private fun footerText(
    info: ErrorInfo,
    context: Context,
): String =
    when (info.source) {
        ErrorSource.PROVIDER -> "A problem with your provider, not AxiomTV"
        ErrorSource.INTERNET -> "A problem with your internet connection"
        ErrorSource.AXIOMTV -> deviceCode(context)?.let { "Device code $it" } ?: "AxiomTV"
    }

private fun deviceCode(context: Context): String? =
    context.getSharedPreferences("axiomtv_licence", Context.MODE_PRIVATE).getString("code", null)

/** Help: a QR code to the error's page on axiomtv.co.uk, and who can fix it. */
@Composable
fun ErrorHelpDialog(
    info: ErrorInfo,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val qr = remember(info.code) { helpQr(info.helpUrl, 420) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            modifier =
                Modifier
                    .padding(24.dp)
                    .widthIn(max = 860.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF111A38))
                    .border(1.dp, ErrorColors.Edge, RoundedCornerShape(24.dp))
                    .padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(210.dp).clip(RoundedCornerShape(16.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    if (qr != null) Image(qr, contentDescription = "QR code for help with error ${info.code}", modifier = Modifier.fillMaxSize())
                }
                Text(info.helpUrl.removePrefix("https://"), color = ErrorColors.Muted, fontSize = 14.sp)
            }
            Column(modifier = Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Help with ${info.title}", color = ErrorColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Scan the code with your phone camera for step-by-step help.", color = ErrorColors.Body, fontSize = 16.sp)
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ErrorColors.Inner).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (info.source) {
                        ErrorSource.PROVIDER -> {
                            Text("Still not working?", color = ErrorColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "This comes from your TV provider, so only they can fix it. Contact your provider and tell them what you see.",
                                color = ErrorColors.Muted,
                                fontSize = 15.sp,
                            )
                        }
                        ErrorSource.INTERNET -> {
                            Text("Still not working?", color = ErrorColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Check your router and that other devices can get online. If none can, contact your internet company.",
                                color = ErrorColors.Muted,
                                fontSize = 15.sp,
                            )
                        }
                        ErrorSource.AXIOMTV -> {
                            Text("Still stuck?", color = ErrorColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("Message g1ggle on Discord and include these codes:", color = ErrorColors.Muted, fontSize = 15.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CodeChip("Error ${info.code}")
                                deviceCode(context)?.let { CodeChip(it) }
                            }
                        }
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .focusRequester(focus)
                            .focusable()
                            .onKeyEvent { event ->
                                val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                                if (event.type == KeyEventType.KeyDown && ok) {
                                    onDismiss()
                                    true
                                } else {
                                    false
                                }
                            }.clip(RoundedCornerShape(12.dp))
                            .background(ErrorColors.Gold)
                            .border(2.dp, ErrorColors.GoldLight, RoundedCornerShape(12.dp))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Text("Back", color = ErrorColors.OnGold, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CodeChip(text: String) {
    Text(
        text,
        color = ErrorColors.Gold,
        fontSize = 15.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x24D9B45A)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private fun helpQr(
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
