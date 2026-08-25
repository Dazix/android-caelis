package com.dazix.intervalometer.ui.nightsky

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dazix.intervalometer.R
import com.dazix.intervalometer.ar.paletteForTheme
import com.dazix.intervalometer.domain.nightsky.CelestialType
import com.dazix.intervalometer.domain.nightsky.NightVisionTheme
import com.dazix.intervalometer.domain.nightsky.SearchResultItem
import com.dazix.intervalometer.ui.AppIcons
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CelestialSearchBar(
    query: String,
    results: List<SearchResultItem>,
    active: Boolean,
    onQueryChange: (String) -> Unit,
    onActiveChange: (Boolean) -> Unit,
    onSelectItem: (SearchResultItem) -> Unit,
    redVisionMode: Boolean,
    nightVisionTheme: NightVisionTheme = NightVisionTheme.AMBER,
    modifier: Modifier = Modifier,
) {
    val palette = paletteForTheme(nightVisionTheme)
    val containerBg = if (redVisionMode) Color(0xFF000000) else Color(0xEE121824)
    val textColor = if (redVisionMode) palette.primary else Color.White
    val subTextColor = if (redVisionMode) palette.secondary else Color(0xFF94A3B8)
    val iconColor = if (redVisionMode) palette.primary else Color(0xFF4DD0E1)
    val dividerColor = if (redVisionMode) palette.primary.copy(alpha = 0.2f) else Color(0x33FFFFFF)

    val searchBarColors = SearchBarDefaults.colors(
        containerColor = containerBg,
        dividerColor = dividerColor,
        inputFieldColors = SearchBarDefaults.inputFieldColors(
            focusedTextColor = textColor,
            unfocusedTextColor = textColor,
            cursorColor = iconColor,
            focusedLeadingIconColor = iconColor,
            unfocusedLeadingIconColor = iconColor,
            focusedTrailingIconColor = subTextColor,
            unfocusedTrailingIconColor = subTextColor,
            focusedPlaceholderColor = subTextColor,
            unfocusedPlaceholderColor = subTextColor,
        ),
    )

    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = { /* Handled via tapping suggestion */ },
                expanded = active,
                onExpandedChange = onActiveChange,
                placeholder = {
                    Text(
                        text = stringResource(R.string.search_celestial_placeholder),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    if (active) {
                        IconButton(
                            onClick = { onActiveChange(false) },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = AppIcons.Back,
                                contentDescription = stringResource(R.string.action_back),
                                tint = textColor,
                            )
                        }
                    } else {
                        Icon(
                            imageVector = AppIcons.Search,
                            contentDescription = null,
                            tint = iconColor,
                        )
                    }
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = AppIcons.Clear,
                                contentDescription = stringResource(R.string.search_celestial_clear),
                                tint = subTextColor,
                            )
                        }
                    }
                },
                colors = searchBarColors.inputFieldColors,
            )
        },
        expanded = active,
        onExpandedChange = onActiveChange,
        colors = searchBarColors,
        shape = if (active) RoundedCornerShape(0.dp) else CircleShape,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (active) 0.dp else 16.dp),
    ) {
        if (query.isNotBlank() && results.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.search_celestial_empty),
                    color = subTextColor,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                items(results, key = { "${it.type}_${it.indexId}_${it.primaryName}" }) { item ->
                    CelestialSearchResultRow(
                        item = item,
                        textColor = textColor,
                        subTextColor = subTextColor,
                        accentColor = iconColor,
                        redVisionMode = redVisionMode,
                        nightVisionTheme = nightVisionTheme,
                        onClick = { onSelectItem(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CelestialSearchResultRow(
    item: SearchResultItem,
    textColor: Color,
    subTextColor: Color,
    accentColor: Color,
    redVisionMode: Boolean,
    nightVisionTheme: NightVisionTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = paletteForTheme(nightVisionTheme)
    val itemIcon = when (item.type) {
        CelestialType.STAR -> AppIcons.Star
        CelestialType.CONSTELLATION -> AppIcons.Constellation
        CelestialType.ASTERISM -> AppIcons.AutoAwesome
    }

    val typeLabel = when (item.type) {
        CelestialType.STAR -> stringResource(R.string.celestial_star_subtitle)
        CelestialType.CONSTELLATION -> stringResource(R.string.celestial_constellation_subtitle)
        CelestialType.ASTERISM -> stringResource(R.string.celestial_asterism_subtitle)
    }

    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
        ),
        leadingContent = {
            Icon(
                imageVector = itemIcon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(24.dp),
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.type == CelestialType.STAR && item.magnitude != null) {
                    Text(
                        text = String.format(Locale.US, "mag %+.1f", item.magnitude),
                        color = subTextColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (redVisionMode) Color(0xFF1E0000) else Color(0x334DD0E1),
                    contentColor = if (redVisionMode) palette.primary else Color(0xFF80DEEA),
                ) {
                    Text(
                        text = typeLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        },
        supportingContent = item.secondaryName?.let { secondary ->
            {
                Text(
                    text = secondary,
                    color = subTextColor,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) {
        Text(
            text = item.primaryName,
            color = textColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
