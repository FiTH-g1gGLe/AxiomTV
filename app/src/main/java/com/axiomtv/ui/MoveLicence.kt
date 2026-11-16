package com.axiomtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.axiomtv.LicenceManager
import com.axiomtv.MoveResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Navy = Color(0xFF111A38)
private val Gold = Color(0xFFD9B45A)
private val TextColour = Color(0xFFE8ECF6)
private val Muted = Color(0xFFB9C2DA)

/** New box: "Already paid?" — move an unlock here with a code from the old box or with the receipt. */
@Composable
fun AlreadyPaidDialog(
    manager: LicenceManager,
    onDismiss: () -> Unit,
) {
    var receipt by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var order by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(receipt) { runCatching { firstFocus.requestFocus() } }

    fun submit() {
        if (working) return
        working = true
        error = null
        scope.launch {
            val result = if (receipt) manager.claimByReceipt(email.trim(), order.trim()) else manager.claimTransfer(code)
            working = false
            when (result) {
                MoveResult.Done -> onDismiss()
                is MoveResult.Failed -> error = result.message
                is MoveResult.Code -> Unit
            }
        }
    }

    DialogCard(onDismiss) {
        Text("Already paid?", color = TextColour, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Move your AxiomTV unlock from your old device to this one.", color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogButton("I have my old device", selected = !receipt) { receipt = false; error = null }
            DialogButton("Use my receipt", selected = receipt) { receipt = true; error = null }
        }
        val fieldColours =
            OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextColour,
                unfocusedTextColor = TextColour,
                focusedBorderColor = Gold,
                unfocusedBorderColor = Color(0x40FFFFFF),
                focusedLabelColor = Gold,
                unfocusedLabelColor = Muted,
                cursorColor = Gold,
            )
        if (!receipt) {
            Text(
                "On your old device go to Settings → Licence → Move to another device, then type the 6-digit code here.",
                color = Muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            TvTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("6-digit code") },
                colors = fieldColours,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(260.dp).focusRequester(firstFocus),
            )
        } else {
            Text(
                "Old device lost or broken? Use the email you paid with and the order number on your receipt.",
                color = Muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            TvTextField(
                value = email,
                onValueChange = { email = it.take(200) },
                label = { Text("Email") },
                colors = fieldColours,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.width(380.dp).focusRequester(firstFocus),
            )
            TvTextField(
                value = order,
                onValueChange = { order = it.take(40) },
                label = { Text("Order number") },
                colors = fieldColours,
                modifier = Modifier.width(380.dp),
            )
        }
        error?.let { Text(it, color = Gold, fontSize = 14.sp, textAlign = TextAlign.Center) }
        val ready = if (receipt) email.contains('@') && order.isNotBlank() else code.length == 6
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogButton(if (working) "Moving…" else "Move to this device", primary = true, enabled = ready && !working) { submit() }
            DialogButton("Cancel", onClick = onDismiss)
        }
    }
}

/** Settings > Licence > Link Discord: the code to type after /verify in the AxiomTV Discord. */
@Composable
fun DiscordLinkDialog(
    manager: LicenceManager,
    onDismiss: () -> Unit,
) {
    var result by remember { mutableStateOf<MoveResult?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { result = manager.discordLinkCode() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    DialogCard(onDismiss) {
        Text("Link Discord", color = TextColour, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        when (val r = result) {
            null -> Text("Getting a code…", color = Muted, fontSize = 16.sp)
            is MoveResult.Failed -> Text(r.message, color = Gold, fontSize = 16.sp, textAlign = TextAlign.Center)
            is MoveResult.Code -> {
                Text("In the AxiomTV Discord, go to #verify and type /verify followed by:", color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
                Text(
                    r.code.chunked(3).joinToString(" "),
                    color = Gold,
                    fontSize = 56.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp,
                )
                val left = ((r.expiresAtMs - now) / 60_000).coerceAtLeast(0)
                Text(
                    if (left > 0) "Works for $left more minute${if (left == 1L) "" else "s"}. You'll get your roles straight away." else "This code has run out. Close and try again.",
                    color = Muted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
            MoveResult.Done -> Unit
        }
        DialogButton("Close", primary = true, onClick = onDismiss)
    }
}

/** Old box: shows the 6-digit code to type on the new box. */
@Composable
fun MoveCodeDialog(
    manager: LicenceManager,
    onDismiss: () -> Unit,
) {
    var result by remember { mutableStateOf<MoveResult?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { result = manager.startTransfer() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    DialogCard(onDismiss) {
        Text("Move to another device", color = TextColour, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        when (val r = result) {
            null -> Text("Getting a code…", color = Muted, fontSize = 16.sp)
            is MoveResult.Failed -> Text(r.message, color = Gold, fontSize = 16.sp, textAlign = TextAlign.Center)
            is MoveResult.Code -> {
                Text("On your new device, open AxiomTV, choose Already paid? → I have my old device, and type:", color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
                Text(
                    r.code.chunked(3).joinToString(" "),
                    color = Gold,
                    fontSize = 56.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp,
                )
                val left = ((r.expiresAtMs - now) / 60_000).coerceAtLeast(0)
                Text(
                    if (left > 0) "Works for $left more minute${if (left == 1L) "" else "s"}. This device locks once the new one is unlocked." else "This code has run out. Close and try again.",
                    color = Muted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                Text("You can move your unlock yourself once every 30 days.", color = Color(0xFF8F9ABB), fontSize = 13.sp)
            }
            MoveResult.Done -> Unit
        }
        DialogButton("Close", primary = true, onClick = onDismiss)
    }
}

@Composable
private fun DialogCard(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier =
                Modifier
                    .padding(24.dp)
                    .width(560.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Navy)
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(24.dp))
                    .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

@Composable
private fun DialogButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val background =
        when {
            primary && !enabled -> Color(0x40D9B45A)
            primary -> if (focused) Gold else Color(0xFFB8964A)
            selected -> Color(0x33D9B45A)
            focused -> Color(0x33FFFFFF)
            else -> Color(0x12FFFFFF)
        }
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(background)
                .border(2.dp, if (focused) Gold else if (selected) Color(0x80D9B45A) else Color(0x24FFFFFF), shape)
                .onFocusChanged { focused = it.isFocused }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ).padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (primary) Color(0xFF1A1406) else TextColour,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
