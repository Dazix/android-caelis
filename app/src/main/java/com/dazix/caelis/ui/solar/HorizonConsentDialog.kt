package com.dazix.caelis.ui.solar

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dazix.caelis.R
import com.dazix.caelis.ui.AppIcons

/**
 * Asks, once, whether the app may fetch elevation tiles.
 *
 * The body says exactly what leaves the device, because that is the only honest way to ask an app
 * that until now had no network permission at all. "Never" is as durable an answer as "Allow" — the
 * question must not come back on the next launch — and it leaves the hand-import path intact.
 */
@Composable
fun HorizonConsentDialog(
    onAllow: () -> Unit,
    onNever: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { /* A deliberate answer is required; dismissing would re-ask forever. */ },
        icon = { Icon(AppIcons.SunAndMoon, contentDescription = null) },
        title = { Text(stringResource(R.string.horizon_consent_title)) },
        text = { Text(stringResource(R.string.horizon_consent_body)) },
        confirmButton = {
            Button(onClick = onAllow) { Text(stringResource(R.string.horizon_consent_allow)) }
        },
        dismissButton = {
            TextButton(onClick = onNever) { Text(stringResource(R.string.horizon_consent_never)) }
        },
    )
}
