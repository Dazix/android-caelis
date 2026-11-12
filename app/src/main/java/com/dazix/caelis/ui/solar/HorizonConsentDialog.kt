package com.dazix.caelis.ui.solar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R
import com.dazix.caelis.ui.AppIcons
import com.dazix.caelis.ui.SwitchRow

/**
 * Asks, once, whether the app may fetch elevation tiles.
 *
 * The body says exactly what leaves the device, because that is the only honest way to ask an app
 * that until now had no network permission at all. "Not now" is as durable an answer as "Allow" — the
 * question must not come back on the next launch — and it leaves the hand-import path intact.
 */
@Composable
fun HorizonConsentDialog(
    initialWifiOnly: Boolean = false,
    onAllow: (wifiOnly: Boolean) -> Unit,
    onNever: () -> Unit,
) {
    var wifiOnly by remember { mutableStateOf(initialWifiOnly) }
    // Held here, not inside the `text` slot: toggling the switch below threw a scrolled body back to
    // the top, so the slot's own remembered state did not survive the recomposition. Hoisted with
    // wifiOnly, the position lives as long as the dialog.
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = { /* A deliberate answer is required; dismissing would re-ask forever. */ },
        icon = { Icon(AppIcons.SunAndMoon, contentDescription = null) },
        title = { Text(stringResource(R.string.horizon_consent_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.horizon_consent_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                SwitchRow(
                    title = stringResource(R.string.horizon_wifi_only),
                    description = stringResource(R.string.horizon_wifi_only_desc),
                    checked = wifiOnly,
                    onCheckedChange = { wifiOnly = it },
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAllow(wifiOnly) }) {
                Text(stringResource(R.string.horizon_consent_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onNever) {
                Text(stringResource(R.string.horizon_consent_never))
            }
        },
    )
}
