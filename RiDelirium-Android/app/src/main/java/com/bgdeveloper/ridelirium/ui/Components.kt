package com.bgdeveloper.ridelirium.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bgdeveloper.ridelirium.ui.theme.Sb
import com.bgdeveloper.ridelirium.ui.theme.sb

/** The headline number with its two-line caption, shared by Stats (88 pt) and Daily stats (56 pt). */
@Composable
fun HeroNumber(value: String, captionLines: List<String>, modifier: Modifier = Modifier, size: Int = 88) {
    val captionSize = if (size == 88) 20 else 15
    val captionBottom = if (size == 88) 8.dp else 5.dp
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            value,
            style = sb(size, FontWeight.ExtraBold, tracking = -0.02f, lineHeight = 0.9f, tabular = true),
            color = Color.White,
            maxLines = 1,
        )
        Column(Modifier.padding(bottom = captionBottom)) {
            captionLines.forEach {
                Text(it, style = sb(captionSize, lineHeight = 1.25f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun Chevron(modifier: Modifier = Modifier, color: Color = com.bgdeveloper.ridelirium.ui.theme.Sb.Chevron, pointsLeft: Boolean = false, size: Dp = 11.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        val (x0, x1) = if (pointsLeft) w * 0.75f to w * 0.25f else w * 0.25f to w * 0.75f
        drawLine(color, Offset(x0, h * 0.1f), Offset(x1, h * 0.5f), stroke.width, stroke.cap)
        drawLine(color, Offset(x1, h * 0.5f), Offset(x0, h * 0.9f), stroke.width, stroke.cap)
    }
}

/** A short confirming tap, like the success haptic on iPhone. */
fun View.successHaptic() {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
}

@Composable
fun statusBarTop(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

@Composable
fun navigationBarBottom(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

@Composable
fun BigButton(title: String, background: Color, foreground: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(20.dp)).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(title, style = sb(22), color = foreground) }
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    TextField(
        value = value, onValueChange = onChange, singleLine = true, modifier = modifier,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, visualTransformation = visualTransformation,
        placeholder = { Text(placeholder, style = sb(17), color = Sb.Secondary) },
        textStyle = sb(18),
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Sb.LightGrey, unfocusedContainerColor = Sb.LightGrey,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}
