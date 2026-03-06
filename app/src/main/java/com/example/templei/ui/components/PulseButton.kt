package com.example.templei.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.templei.ui.theme.TempleITheme

@Composable
fun PulseButton(
    isFlashing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "pulseTransition")
    val pulseScale = transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isFlashing) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(550),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val containerColor = animateColorAsState(
        targetValue = if (isFlashing) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        label = "pulseColor"
    )

    Button(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minWidth = 180.dp)
            .graphicsLayer {
                val animatedScale = if (isFlashing) pulseScale.value else 1f
                scaleX = animatedScale
                scaleY = animatedScale
            },
        colors = ButtonDefaults.buttonColors(containerColor = containerColor.value)
    ) {
        Text(if (isFlashing) "Stop Flash" else "Start Flash")
    }
}

@Preview(showBackground = true)
@Composable
private fun PulseButtonPreview() {
    TempleITheme {
        PulseButton(isFlashing = true, onClick = {})
    }
}
