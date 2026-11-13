package com.github.damontecres.wholphin.axiom.ui

import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object AxiomColors {
    val Gold = Color(0xFFE5A00D)
    val OnGold = Color(0xFF1A1300)
    val Background = Color(0xFF0F1012)
    val Panel = Color(0xFF16181C)
    val PanelHigh = Color(0xFF23262C)
    val Text = Color(0xFFF2F3F5)
    val TextMuted = Color(0xFFA9ADB4)
    val GoldTint = Color(0x33E5A00D)
    val Error = Color(0xFFFF8A80)
}

@Composable
fun AxiomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = AxiomColors.Gold,
                onPrimary = AxiomColors.OnGold,
                background = AxiomColors.Background,
                onBackground = AxiomColors.Text,
                surface = AxiomColors.Background,
                onSurface = AxiomColors.Text,
                surfaceVariant = AxiomColors.Panel,
                onSurfaceVariant = AxiomColors.TextMuted,
                error = AxiomColors.Error,
            ),
        content = content,
    )
}

/**
 * A row that works with both a TV remote (glows gold when focused) and touch.
 */
@Composable
fun FocusRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val background =
        when {
            focused -> AxiomColors.Gold
            selected -> AxiomColors.GoldTint
            else -> Color.Transparent
        }
    val contentColor = if (focused) AxiomColors.OnGold else AxiomColors.Text
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(background)
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/** A pill-shaped button for toolbars; gold outline when focused. */
@Composable
fun AxiomPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    icon: ImageVector? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val background =
        when {
            focused -> AxiomColors.Gold
            highlighted -> AxiomColors.Gold
            else -> AxiomColors.PanelHigh
        }
    val textColor = if (focused || highlighted) AxiomColors.OnGold else AxiomColors.Text
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(50))
                .background(background)
                .border(
                    width = 2.dp,
                    color = if (focused) AxiomColors.Text else Color.Transparent,
                    shape = RoundedCornerShape(50),
                ).onFocusChanged { focused = it.isFocused }
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = if (text.isEmpty()) 12.dp else 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, contentDescription = text.ifEmpty { null }, tint = if (enabled) textColor else AxiomColors.TextMuted, modifier = Modifier.size(20.dp))
            if (text.isNotEmpty()) Spacer(Modifier.width(8.dp))
        }
        if (text.isNotEmpty()) Text(
            text = text,
            color = if (enabled) textColor else AxiomColors.TextMuted,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Brand font (Michroma, SIL Open Font License) */
val AxiomBrandFont = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(com.github.damontecres.wholphin.R.font.michroma))

/** "AXIOMTV" name in the brand font, TV in gold. */
@Composable
fun AxiomWordmark(
    size: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    Text(
        text =
            androidx.compose.ui.text.buildAnnotatedString {
                append("AXIOM")
                pushStyle(androidx.compose.ui.text.SpanStyle(color = AxiomColors.Gold))
                append("TV")
                pop()
            },
        color = Color(0xFFF2F2F4),
        fontFamily = AxiomBrandFont,
        fontSize = size,
        letterSpacing = androidx.compose.ui.unit.TextUnit(0.12f, androidx.compose.ui.unit.TextUnitType.Em),
        maxLines = 1,
        modifier = modifier,
    )
}

/** Gold ring + soft gold glow when focused (glow needs Android 9+; older boxes just get the ring). */
fun Modifier.glowFocus(
    focused: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
): Modifier =
    if (!focused) {
        this
    } else {
        this
            .shadow(elevation = 22.dp, shape = shape, ambientColor = AxiomColors.Gold, spotColor = AxiomColors.Gold)
            .border(3.dp, AxiomColors.Gold, shape)
    }
