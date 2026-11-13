package com.github.damontecres.wholphin.axiom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.github.damontecres.wholphin.axiom.PhoneLoginServer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LoginScreen(viewModel: AxiomViewModel) {
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    // "Sign in with your phone": a little web page served by the box on your home Wi-Fi
    val phoneServer =
        remember {
            PhoneLoginServer { srv, user, pass ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    server = srv
                    username = user
                    password = pass
                    viewModel.signIn(srv, user, pass)
                }
            }
        }
    var phoneAddress by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        phoneAddress = phoneServer.start()
        onDispose { phoneServer.stop() }
    }
    val phoneCode by produceState(phoneServer.code) {
        while (true) {
            value = phoneServer.code
            kotlinx.coroutines.delay(1000)
        }
    }

    val fieldColors =
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AxiomColors.Gold,
            focusedLabelColor = AxiomColors.Gold,
            cursorColor = AxiomColors.Gold,
            unfocusedBorderColor = AxiomColors.PanelHigh,
        )

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.github.damontecres.wholphin.R.drawable.axiom_logo),
                contentDescription = null,
                modifier = Modifier.width(140.dp),
            )
            Text(
                text =
                    buildAnnotatedString {
                        append("Axiom")
                        withStyle(SpanStyle(color = AxiomColors.Gold)) { append("TV") }
                    },
                fontSize = 40.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = "Sign in with your Xtream Codes login, or use your phone",
                color = AxiomColors.TextMuted,
                fontSize = 16.sp,
            )
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("Server address") },
                placeholder = { Text("e.g. http://example.com:8080") },
                singleLine = true,
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                colors = fieldColors,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions =
                    KeyboardActions(onDone = {
                        focusManager.clearFocus()
                        viewModel.signIn(server, username, password)
                    }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.signIn(server, username, password) },
                enabled = !viewModel.busy,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = AxiomColors.Gold,
                        contentColor = AxiomColors.OnGold,
                    ),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Sign in", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
            }
            if (viewModel.busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = AxiomColors.Gold, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(viewModel.busyMessage, color = AxiomColors.TextMuted)
                }
            }
            viewModel.error?.let { message ->
                Text(message, color = AxiomColors.Error, fontSize = 16.sp)
            }
        }
        phoneAddress?.let { address -> PhoneSignInPanel(address, phoneCode) }
        }
    }
}

/** QR code + address + code, for typing your login on your phone instead of the remote. */
@Composable
private fun PhoneSignInPanel(
    address: String,
    code: String,
) {
    val qr = remember(address) { qrBitmap(address, 480) }
    Column(
        modifier =
            Modifier
                .width(340.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(AxiomColors.Panel)
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sign in with your phone", color = AxiomColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Scan the code (phone on the same Wi-Fi) and type your login there.",
            color = AxiomColors.TextMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        qr?.let {
            androidx.compose.foundation.Image(
                bitmap = it,
                contentDescription = "QR code for $address",
                modifier = Modifier.size(200.dp).clip(RoundedCornerShape(12.dp)),
            )
        }
        Text(address, color = AxiomColors.Gold, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("Code", color = AxiomColors.TextMuted, fontSize = 13.sp)
        Text(code, color = AxiomColors.Text, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 10.sp)
    }
}

private fun qrBitmap(
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
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()
