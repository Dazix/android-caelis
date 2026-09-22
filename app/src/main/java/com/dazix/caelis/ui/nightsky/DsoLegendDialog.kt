package com.dazix.caelis.ui.nightsky

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dazix.caelis.R
import com.dazix.caelis.ar.paletteForTheme
import com.dazix.caelis.data.nightsky.DsoType
import com.dazix.caelis.domain.nightsky.NightVisionTheme

/**
 * Accurately draws a type-specific Deep Sky Object glyph using Compose [Canvas].
 * Matches the zero-allocation 60 FPS rendering in [NightSkyRenderer].
 */
@Composable
fun DsoGlyphCanvas(
    type: Byte,
    primaryColor: Color,
    haloColor: Color,
    coreColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val strokeWidth = 1.5.dp.toPx()
        val haloWidth = 3.5.dp.toPx()

        when (type) {
            DsoType.GALAXY -> {
                // Tilted ellipse (major:minor = 2:1) angled at -35°
                rotate(degrees = -35f, pivot = center) {
                    val radius = size.minDimension / 2f * 0.78f
                    val rect = Rect(
                        left = center.x - radius,
                        top = center.y - radius * 0.5f,
                        right = center.x + radius,
                        bottom = center.y + radius * 0.5f,
                    )
                    drawOval(
                        color = haloColor,
                        topLeft = rect.topLeft,
                        size = rect.size,
                        style = Stroke(width = haloWidth),
                    )
                    drawOval(
                        color = primaryColor,
                        topLeft = rect.topLeft,
                        size = rect.size,
                        style = Stroke(width = strokeWidth),
                    )
                    drawCircle(
                        color = coreColor,
                        radius = 2.dp.toPx(),
                        center = center,
                    )
                }
            }
            DsoType.GLOBULAR_CLUSTER -> {
                // Circle with inscribed crosshair
                val radius = size.minDimension / 2f * 0.72f
                drawCircle(
                    color = haloColor,
                    radius = radius,
                    center = center,
                    style = Stroke(width = haloWidth),
                )
                drawCircle(
                    color = primaryColor,
                    radius = radius,
                    center = center,
                    style = Stroke(width = strokeWidth),
                )
                drawLine(
                    color = primaryColor,
                    start = Offset(center.x - radius, center.y),
                    end = Offset(center.x + radius, center.y),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = primaryColor,
                    start = Offset(center.x, center.y - radius),
                    end = Offset(center.x, center.y + radius),
                    strokeWidth = strokeWidth,
                )
                drawCircle(
                    color = coreColor,
                    radius = 2.dp.toPx(),
                    center = center,
                )
            }
            DsoType.OPEN_CLUSTER -> {
                // Dashed circle with center core
                val radius = size.minDimension / 2f * 0.72f
                drawCircle(
                    color = haloColor,
                    radius = radius,
                    center = center,
                    style = Stroke(width = haloWidth),
                )
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.5.dp.toPx()), 0f)
                drawCircle(
                    color = primaryColor,
                    radius = radius,
                    center = center,
                    style = Stroke(width = strokeWidth, pathEffect = dashEffect),
                )
                drawCircle(
                    color = coreColor,
                    radius = 2.dp.toPx(),
                    center = center,
                )
            }
            DsoType.NEBULA -> {
                // Rounded square bounding frame
                val r = size.minDimension / 2f * 0.65f
                val rect = Rect(center.x - r, center.y - r, center.x + r, center.y + r)
                drawRoundRect(
                    color = haloColor,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = Stroke(width = haloWidth),
                )
                drawRoundRect(
                    color = primaryColor,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = Stroke(width = strokeWidth),
                )
                drawCircle(
                    color = coreColor,
                    radius = 1.8.dp.toPx(),
                    center = center,
                )
            }
            DsoType.PLANETARY_NEBULA -> {
                // Circle with 4 outward tick marks
                val radius = size.minDimension / 2f * 0.65f
                drawCircle(
                    color = haloColor,
                    radius = radius,
                    center = center,
                    style = Stroke(width = haloWidth),
                )
                drawCircle(
                    color = primaryColor,
                    radius = radius,
                    center = center,
                    style = Stroke(width = strokeWidth),
                )
                val tick = 4.dp.toPx()
                drawLine(
                    color = primaryColor,
                    start = Offset(center.x - radius - tick, center.y),
                    end = Offset(center.x - radius, center.y),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = primaryColor,
                    start = Offset(center.x + radius, center.y),
                    end = Offset(center.x + radius + tick, center.y),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = primaryColor,
                    start = Offset(center.x, center.y - radius - tick),
                    end = Offset(center.x, center.y - radius),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = primaryColor,
                    start = Offset(center.x, center.y + radius),
                    end = Offset(center.x, center.y + radius + tick),
                    strokeWidth = strokeWidth,
                )
            }
            else -> {
                val radius = size.minDimension / 2f * 0.7f
                drawCircle(
                    color = primaryColor,
                    radius = radius,
                    center = center,
                    style = Stroke(width = strokeWidth),
                )
            }
        }
    }
}

/**
 * Individual row in the DSO symbol legend.
 */
@Composable
fun DsoLegendRow(
    type: Byte,
    title: String,
    description: String,
    examples: String,
    primaryColor: Color,
    haloColor: Color,
    coreColor: Color,
    titleColor: Color,
    descColor: Color,
    examplesColor: Color,
    badgeBgColor: Color,
    badgeBorderColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(badgeBgColor)
                .border(0.8.dp, badgeBorderColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DsoGlyphCanvas(
                type = type,
                primaryColor = primaryColor,
                haloColor = haloColor,
                coreColor = coreColor,
                modifier = Modifier.size(34.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                fontSize = 15.sp,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = descColor,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
            Text(
                text = examples,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = examplesColor,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

/**
 * Scrollable content containing all 5 Deep Sky Object legend categories.
 */
@Composable
fun DsoLegendContent(
    redVisionMode: Boolean,
    nightVisionTheme: NightVisionTheme = NightVisionTheme.AMBER,
    modifier: Modifier = Modifier,
) {
    val palette = paletteForTheme(nightVisionTheme)
    val primaryColor = if (redVisionMode) palette.primary else Color(0xFF4DD0E1)
    val haloColor = if (redVisionMode) palette.primary.copy(alpha = 0.2f) else Color(0x334DD0E1)
    val coreColor = if (redVisionMode) palette.primary else Color(0xFFFFFFFF)
    val titleColor = if (redVisionMode) palette.primary else Color(0xFFF1F5F9)
    val descColor = if (redVisionMode) palette.primary.copy(alpha = 0.85f) else Color(0xFFCBD5E1)
    val examplesColor = if (redVisionMode) palette.secondary else Color(0xFF94A3B8)
    val badgeBgColor = if (redVisionMode) Color(0xFF1E0505) else Color(0xFF1E293B)
    val badgeBorderColor = if (redVisionMode) palette.primary.copy(alpha = 0.35f) else Color(0x444DD0E1)
    val dividerColor = if (redVisionMode) palette.primary.copy(alpha = 0.15f) else Color(0x22475569)

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        // 1. Galaxies
        DsoLegendRow(
            type = DsoType.GALAXY,
            title = stringResource(R.string.dso_legend_galaxy_title),
            description = stringResource(R.string.dso_legend_galaxy_desc),
            examples = stringResource(R.string.dso_legend_galaxy_examples),
            primaryColor = primaryColor,
            haloColor = haloColor,
            coreColor = coreColor,
            titleColor = titleColor,
            descColor = descColor,
            examplesColor = examplesColor,
            badgeBgColor = badgeBgColor,
            badgeBorderColor = badgeBorderColor,
        )
        HorizontalDivider(color = dividerColor, thickness = 0.5.dp)

        // 2. Globular Clusters
        DsoLegendRow(
            type = DsoType.GLOBULAR_CLUSTER,
            title = stringResource(R.string.dso_legend_globular_cluster_title),
            description = stringResource(R.string.dso_legend_globular_cluster_desc),
            examples = stringResource(R.string.dso_legend_globular_cluster_examples),
            primaryColor = primaryColor,
            haloColor = haloColor,
            coreColor = coreColor,
            titleColor = titleColor,
            descColor = descColor,
            examplesColor = examplesColor,
            badgeBgColor = badgeBgColor,
            badgeBorderColor = badgeBorderColor,
        )
        HorizontalDivider(color = dividerColor, thickness = 0.5.dp)

        // 3. Open Clusters
        DsoLegendRow(
            type = DsoType.OPEN_CLUSTER,
            title = stringResource(R.string.dso_legend_open_cluster_title),
            description = stringResource(R.string.dso_legend_open_cluster_desc),
            examples = stringResource(R.string.dso_legend_open_cluster_examples),
            primaryColor = primaryColor,
            haloColor = haloColor,
            coreColor = coreColor,
            titleColor = titleColor,
            descColor = descColor,
            examplesColor = examplesColor,
            badgeBgColor = badgeBgColor,
            badgeBorderColor = badgeBorderColor,
        )
        HorizontalDivider(color = dividerColor, thickness = 0.5.dp)

        // 4. Diffuse Nebulae & Supernova Remnants
        DsoLegendRow(
            type = DsoType.NEBULA,
            title = stringResource(R.string.dso_legend_diffuse_nebula_title),
            description = stringResource(R.string.dso_legend_diffuse_nebula_desc),
            examples = stringResource(R.string.dso_legend_diffuse_nebula_examples),
            primaryColor = primaryColor,
            haloColor = haloColor,
            coreColor = coreColor,
            titleColor = titleColor,
            descColor = descColor,
            examplesColor = examplesColor,
            badgeBgColor = badgeBgColor,
            badgeBorderColor = badgeBorderColor,
        )
        HorizontalDivider(color = dividerColor, thickness = 0.5.dp)

        // 5. Planetary Nebulae
        DsoLegendRow(
            type = DsoType.PLANETARY_NEBULA,
            title = stringResource(R.string.dso_legend_planetary_nebula_title),
            description = stringResource(R.string.dso_legend_planetary_nebula_desc),
            examples = stringResource(R.string.dso_legend_planetary_nebula_examples),
            primaryColor = primaryColor,
            haloColor = haloColor,
            coreColor = coreColor,
            titleColor = titleColor,
            descColor = descColor,
            examplesColor = examplesColor,
            badgeBgColor = badgeBgColor,
            badgeBorderColor = badgeBorderColor,
        )
    }
}

/**
 * Modal dialog presenting the Deep Sky Objects symbol legend.
 */
@Composable
fun DsoLegendDialog(
    onDismiss: () -> Unit,
    redVisionMode: Boolean,
    nightVisionTheme: NightVisionTheme = NightVisionTheme.AMBER,
) {
    val palette = paletteForTheme(nightVisionTheme)
    val dialogBgColor = if (redVisionMode) Color(0xFF140505) else Color(0xFF131823)
    val titleColor = if (redVisionMode) palette.primary else Color.White
    val buttonColor = if (redVisionMode) palette.primary else Color(0xFF4DD0E1)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = dialogBgColor,
        title = {
            Text(
                text = stringResource(R.string.dso_legend_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = titleColor,
            )
        },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
            ) {
                DsoLegendContent(
                    redVisionMode = redVisionMode,
                    nightVisionTheme = nightVisionTheme,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.dso_legend_close),
                    color = buttonColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}
