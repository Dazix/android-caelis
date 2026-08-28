package com.dazix.caelis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R

/**
 * The app list. Its own scaffold rather than [ScreenScaffold] because the search field belongs under
 * the app bar and above the list, where it does not scroll away, and because a lazy list must own the
 * whole scroll rather than sit inside a scrolling column.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppPickerScreen(
    apps: List<AppEntry>,
    query: String,
    loading: Boolean,
    selectedPackage: String?,
    onQueryChange: (String) -> Unit,
    onSelect: (AppEntry) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    val cameras = apps.filter { it.isCamera }
    val others = apps.filterNot { it.isCamera }
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // The same expressive bar every other screen uses via [ScreenScaffold]: it sizes itself
            // from its content instead of from a fixed token height, so the title can grow with the
            // font scale rather than being clipped inside a bar that cannot.
            MediumFlexibleTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.picker_title),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = AppIcons.Back,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (selectedPackage != null) {
                        TextButton(onClick = onClear) {
                            Text(stringResource(R.string.picker_clear))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.picker_search)) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                imageVector = AppIcons.Clear,
                                contentDescription = stringResource(R.string.picker_search_clear),
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Text(
                text = stringResource(R.string.picker_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            when {
                loading -> Text(
                    text = stringResource(R.string.picker_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                apps.isEmpty() -> Text(
                    text = stringResource(R.string.picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    if (cameras.isNotEmpty()) {
                        item(key = "header-camera") {
                            SectionHeader(stringResource(R.string.picker_section_camera))
                        }
                        items(cameras, key = { "camera-${it.packageName}" }) { entry ->
                            AppRow(entry, entry.packageName == selectedPackage, onSelect)
                        }
                    }
                    if (others.isNotEmpty()) {
                        item(key = "header-all") {
                            SectionHeader(stringResource(R.string.picker_section_all))
                        }
                        items(others, key = { "all-${it.packageName}" }) { entry ->
                            AppRow(entry, entry.packageName == selectedPackage, onSelect)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Column {
        HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun AppRow(entry: AppEntry, selected: Boolean, onSelect: (AppEntry) -> Unit) {
    // The selected row is a filled container as well as a badge, so the state is never a colour alone
    // and never a badge that scrolled out of view.
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(MaterialTheme.shapes.large)
                .clickable { onSelect(entry) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (entry.isCamera) {
                    Icon(
                        imageVector = AppIcons.Session,
                        contentDescription = null,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Text(
                        text = entry.label.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    // Derived from the row's own content colour, which is onSecondaryContainer once
                    // the row is selected: naming onSurfaceVariant here put unrelated text on a
                    // tonal container, which a dynamic scheme can turn into poor contrast.
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (entry.isCamera) Badge(stringResource(R.string.picker_badge_camera))
                if (selected) {
                    Badge(
                        text = stringResource(R.string.picker_selected),
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}
