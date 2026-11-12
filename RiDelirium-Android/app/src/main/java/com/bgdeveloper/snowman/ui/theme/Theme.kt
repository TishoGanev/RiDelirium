package com.bgdeveloper.snowman.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.bgdeveloper.snowman.R

object Sb {
    val Red = Color(0xFFED213D)
    val Charcoal = Color(0xFF353535)
    val Secondary = Color(0xFF8A8A8A)
    val LightGrey = Color(0xFFF2F2F2)
    val GreyButtonText = Color(0xFF4D4D4D)
    val Divider = Color(0xFFE8E8E8)
    val Chevron = Color(0xFFBDBDBD)
    val MapBackground = Color(0xFF141B24)
    val Sun = Color(0xFFFFB340)
    val UserDot = Color(0xFF1A7CFF)
    val Lift = Color(0xFF4DA3FF)
    val Still = Color(0xFFB8B8B8)
}

val SbFontFamily = FontFamily(
    Font(R.font.opensans_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.opensans_condensed_bold, FontWeight.Bold),
    Font(R.font.opensans_condensed_extrabold, FontWeight.ExtraBold),
)

/**
 * Text style from the design spec. [tracking] is in em (the design's letter-spacing),
 * [lineHeight] a multiple of the font size (CSS line-height).
 */
fun sb(
    size: Int,
    weight: FontWeight = FontWeight.Bold,
    tracking: Float = 0f,
    lineHeight: Float = 1.15f,
    tabular: Boolean = false,
) = TextStyle(
    fontFamily = SbFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    letterSpacing = (size * tracking).sp,
    lineHeight = (size * lineHeight).sp,
    fontFeatureSettings = if (tabular) "tnum" else null,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

@Composable
fun SnowmanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Sb.Red,
            background = Color.White,
            surface = Color.White,
            onSurface = Sb.Charcoal,
        ),
        content = content,
    )
}
