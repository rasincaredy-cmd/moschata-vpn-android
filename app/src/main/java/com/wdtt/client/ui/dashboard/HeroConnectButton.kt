package com.wdtt.client.ui.dashboard

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

enum class ConnectionVisualState {
    IDLE,
    CONNECTING,
    CONNECTED_WIREGUARD,
    CONNECTED_BYPASS,
    DISCONNECTING
}

@Composable
fun HeroConnectButton(
    visualState: ConnectionVisualState,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (visualState == ConnectionVisualState.CONNECTING) 1.25f else 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (visualState == ConnectionVisualState.CONNECTING) 1000 else 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (visualState == ConnectionVisualState.CONNECTING) 1000 else 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val emerald = Color(0xFF00E676)
    val electricViolet = Color(0xFF9D4EDD)
    val amber = Color(0xFFFFA726)
    val slate = Color(0xFF2C2C35)
    val slateBorder = Color(0xFF454552)

    val activeColor by animateColorAsState(
        targetValue = when (visualState) {
            ConnectionVisualState.IDLE -> slate
            ConnectionVisualState.CONNECTING -> amber
            ConnectionVisualState.CONNECTED_WIREGUARD -> emerald
            ConnectionVisualState.CONNECTED_BYPASS -> electricViolet
            ConnectionVisualState.DISCONNECTING -> Color(0xFFFF5252)
        },
        animationSpec = tween(500),
        label = "activeColor"
    )

    val glowColor = when (visualState) {
        ConnectionVisualState.IDLE -> Color.Transparent
        ConnectionVisualState.CONNECTING -> amber
        ConnectionVisualState.CONNECTED_WIREGUARD -> emerald
        ConnectionVisualState.CONNECTED_BYPASS -> electricViolet
        ConnectionVisualState.DISCONNECTING -> Color(0xFFFF5252)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(220.dp)
    ) {
        if (visualState != ConnectionVisualState.IDLE) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(pulseScale)
            ) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = pulseAlpha * 0.45f),
                            glowColor.copy(alpha = 0f)
                        ),
                        radius = size.minDimension / 1.8f
                    )
                )
            }
        }

        Canvas(modifier = Modifier.size(176.dp)) {
            drawCircle(
                color = when (visualState) {
                    ConnectionVisualState.IDLE -> slateBorder
                    else -> activeColor.copy(alpha = 0.35f)
                },
                style = Stroke(width = 3.dp.toPx())
            )
        }

        Surface(
            shape = CircleShape,
            color = when (visualState) {
                ConnectionVisualState.IDLE -> Color(0xFF1E1E26)
                ConnectionVisualState.CONNECTING -> Color(0xFF2B2215)
                ConnectionVisualState.CONNECTED_WIREGUARD -> Color(0xFF0C291A)
                ConnectionVisualState.CONNECTED_BYPASS -> Color(0xFF221133)
                ConnectionVisualState.DISCONNECTING -> Color(0xFF2B1515)
            },
            shadowElevation = if (visualState != ConnectionVisualState.IDLE) 12.dp else 4.dp,
            border = androidx.compose.foundation.BorderStroke(
                width = 2.dp,
                color = when (visualState) {
                    ConnectionVisualState.IDLE -> slateBorder
                    else -> activeColor
                }
            ),
            modifier = Modifier
                .size(156.dp)
                .clip(CircleShape)
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onClick()
                }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = when (visualState) {
                        ConnectionVisualState.CONNECTED_BYPASS -> Icons.Filled.FlashOn
                        ConnectionVisualState.CONNECTED_WIREGUARD -> Icons.Filled.Shield
                        else -> Icons.Filled.PowerSettingsNew
                    },
                    contentDescription = "Подключение",
                    tint = when (visualState) {
                        ConnectionVisualState.IDLE -> Color(0xFF9E9EA8)
                        else -> activeColor
                    },
                    modifier = Modifier.size(56.dp)
                )
            }
        }
    }
}
