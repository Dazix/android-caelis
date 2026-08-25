package com.dazix.intervalometer.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface AppToastContent {
    data class Text(val value: String) : AppToastContent
    data class Resource(@StringRes val resId: Int) : AppToastContent
}

data class AppToastMessage(
    val id: Long,
    val content: AppToastContent,
    val icon: ImageVector? = null,
)

@Stable
class AppToastHostState(private val coroutineScope: CoroutineScope) {
    var currentToast by mutableStateOf<AppToastMessage?>(null)
        private set

    private var dismissJob: Job? = null
    private var messageIdCounter = 0L

    fun showToast(text: String, icon: ImageVector? = null, durationMs: Long = 2200L) {
        dismissJob?.cancel()
        val id = ++messageIdCounter
        currentToast = AppToastMessage(id = id, content = AppToastContent.Text(text), icon = icon)
        dismissJob = coroutineScope.launch {
            delay(durationMs)
            if (currentToast?.id == id) {
                currentToast = null
            }
        }
    }

    fun showToast(@StringRes resId: Int, icon: ImageVector? = null, durationMs: Long = 2200L) {
        dismissJob?.cancel()
        val id = ++messageIdCounter
        currentToast = AppToastMessage(id = id, content = AppToastContent.Resource(resId), icon = icon)
        dismissJob = coroutineScope.launch {
            delay(durationMs)
            if (currentToast?.id == id) {
                currentToast = null
            }
        }
    }

    fun dismiss() {
        dismissJob?.cancel()
        currentToast = null
    }
}

val LocalToastHostState = staticCompositionLocalOf<AppToastHostState?> { null }

/**
 * Universal, theme-aware Toast component that automatically respects the active color mode:
 * - In Light theme: high-contrast dark inverse surface with clear readable text.
 * - In Dark theme: high-contrast light inverse surface or themed dark container.
 * - In Night Sky / Red Vision mode: dark pure black surface with theme-tinted primary accents.
 * - In Sun AR mode: golden solar-accented surface.
 */
@Composable
fun AppToastHost(
    hostState: AppToastHostState,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.inverseSurface,
    contentColor: Color = MaterialTheme.colorScheme.inverseOnSurface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
) {
    val toast = hostState.currentToast

    AnimatedVisibility(
        visible = toast != null,
        enter = fadeIn() + scaleIn(initialScale = 0.88f) + slideInVertically { it / 2 },
        exit = fadeOut() + scaleOut(targetScale = 0.88f) + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        if (toast != null) {
            val displayText = when (val content = toast.content) {
                is AppToastContent.Text -> content.value
                is AppToastContent.Resource -> stringResource(content.resId)
            }
            Surface(
                shape = CircleShape,
                color = containerColor,
                contentColor = contentColor,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, borderColor),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    if (toast.icon != null) {
                        Icon(
                            imageVector = toast.icon,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}
