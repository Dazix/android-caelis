package com.dazix.caelis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dazix.caelis.R

// The building blocks of the AR screens' bottom sheets. Sun AR and Night Sky draw their settings from
// the same parts so the two sheets look and behave alike; each screen passes its own colours (Night
// Sky's night vision tints them all).

/**
 * A value stepped through fixed choices, read out between a back and a forward button, in a toggle
 * row's place: icon, label, then the control where the switch would be.
 */
@Composable
internal fun LayerStepperRow(
    label: String,
    icon: ImageVector,
    valueText: String,
    headlineColor: Color,
    iconColor: Color,
    canDecrease: Boolean,
    canIncrease: Boolean,
    decreaseDescription: String,
    increaseDescription: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    supportingText: String? = null,
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp),
            )
        },
        supportingContent = supportingText?.let {
            { Text(text = it, color = headlineColor.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall) }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDecrease, enabled = canDecrease, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = AppIcons.Back,
                        contentDescription = decreaseDescription,
                        tint = if (canDecrease) iconColor else iconColor.copy(alpha = 0.38f),
                    )
                }
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = iconColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(56.dp),
                )
                IconButton(onClick = onIncrease, enabled = canIncrease, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = AppIcons.Forward,
                        contentDescription = increaseDescription,
                        tint = if (canIncrease) iconColor else iconColor.copy(alpha = 0.38f),
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    ) {
        Text(
            text = label,
            color = headlineColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Container card for a logical group of layer settings. */
@Composable
internal fun LayerGroupCard(
    title: String,
    titleColor: Color,
    containerColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
    /** Drawn before the title in its colour; decorative, the title says what the group is. */
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    trailingHeader: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                if (leadingIcon != null) {
                    androidx.compose.material3.Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = titleColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = title,
                    color = titleColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            trailingHeader?.invoke()
        }
        content()
    }
}

@Composable
internal fun NudgeRow(
    label: String,
    valueText: String,
    decreaseDescription: String,
    increaseDescription: String,
    accentColor: Color,
    headlineColor: Color,
    onCoarseDecrease: () -> Unit,
    onFineDecrease: () -> Unit,
    onFineIncrease: () -> Unit,
    onCoarseIncrease: () -> Unit,
    enabled: Boolean = true,
) {
    @Composable
    fun NudgeButton(glyph: String, description: String, onClick: () -> Unit) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(44.dp).semantics { contentDescription = description },
        ) {
            Text(text = glyph, style = MaterialTheme.typography.labelLarge, color = if (enabled) accentColor else accentColor.copy(alpha = 0.38f))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = headlineColor,
            modifier = Modifier.weight(1f),
        )
        NudgeButton("⏪", decreaseDescription, onCoarseDecrease)
        NudgeButton("◀", decreaseDescription, onFineDecrease)
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelMedium,
            color = accentColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(56.dp),
        )
        NudgeButton("▶", increaseDescription, onFineIncrease)
        NudgeButton("⏩", increaseDescription, onCoarseIncrease)
    }
}

@Composable
internal fun LayerToggleRow(
    label: String,
    icon: ImageVector,
    isChecked: Boolean,
    headlineColor: Color,
    iconColor: Color,
    switchColors: androidx.compose.material3.SwitchColors,
    onCheckedChange: (Boolean) -> Unit,
    onInfoClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    /** A line under the label saying what the switch does, where its name alone does not. */
    supportingText: String? = null,
) {
    ListItem(
        supportingContent = supportingText?.let {
            {
                Text(
                    text = it,
                    color = headlineColor.copy(alpha = if (enabled) 0.7f else 0.4f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp),
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (onInfoClick != null) {
                    IconButton(
                        onClick = onInfoClick,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Info,
                            contentDescription = stringResource(R.string.dso_legend_button),
                            tint = iconColor,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Switch(
                    checked = isChecked,
                    onCheckedChange = onCheckedChange,
                    enabled = enabled,
                    colors = switchColors,
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!isChecked) }
            .padding(horizontal = 4.dp),
    ) {
        Text(
            text = label,
            color = if (enabled) headlineColor else headlineColor.copy(alpha = 0.5f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** A labelled slider with its value read out, indented to line up with the toggle rows' text. */
@Composable
internal fun LayerSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    headlineColor: Color,
    accentColor: Color,
    sliderColors: SliderColors,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 60.dp, end = 20.dp, bottom = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = headlineColor,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = valueText,
                color = accentColor,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            colors = sliderColors,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { contentDescription = label },
        )
    }
}

/** "1.6", "3", "50": metres above terrain, a decimal only where there is one, in the user's locale. */
internal fun formatHeightMeters(cm: Int): String =
    if (cm % 100 == 0) (cm / 100).toString() else String.format(java.util.Locale.getDefault(), "%.1f", cm / 100.0)

/**
 * The height above terrain, stepped through [com.dazix.caelis.data.HorizonSettings.HEIGHT_CHOICES_CM].
 * A height not among them (none today) steps to the nearest choice on either side.
 */
@Composable
internal fun HeightAboveTerrainRow(
    heightCm: Int,
    onChange: (Int) -> Unit,
    headlineColor: Color,
    iconColor: Color,
) {
    val choices = com.dazix.caelis.data.HorizonSettings.HEIGHT_CHOICES_CM
    val lower = choices.lastOrNull { it < heightCm }
    val higher = choices.firstOrNull { it > heightCm }
    LayerStepperRow(
        label = stringResource(R.string.horizon_height_above_terrain),
        icon = AppIcons.TerrainHorizon,
        valueText = stringResource(R.string.horizon_height_above_terrain_value, formatHeightMeters(heightCm)),
        supportingText = stringResource(R.string.horizon_height_above_terrain_desc),
        headlineColor = headlineColor,
        iconColor = iconColor,
        canDecrease = lower != null,
        canIncrease = higher != null,
        decreaseDescription = stringResource(R.string.horizon_height_decrease),
        increaseDescription = stringResource(R.string.horizon_height_increase),
        onDecrease = { lower?.let(onChange) },
        onIncrease = { higher?.let(onChange) },
    )
}
