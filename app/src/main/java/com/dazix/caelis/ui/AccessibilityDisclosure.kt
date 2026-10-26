package com.dazix.caelis.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.dazix.caelis.R

/**
 * The prominent disclosure Google Play requires before an app that is not an accessibility tool sends
 * the user to switch its accessibility service on. Returns the function every "open accessibility
 * settings" button calls instead of opening them: it shows what the service reads and does, and only
 * an explicit "Agree" runs [onConsent]. Going to Settings without that click does not count as consent,
 * so there is no path from a button straight to the settings screen.
 *
 * Asked every time rather than remembered: the button is only there while the service is off, and an
 * answer given before the service was switched off once is not consent to switching it on again.
 */
@Composable
fun rememberAccessibilityDisclosure(onConsent: () -> Unit): () -> Unit {
    var showing by rememberSaveable { mutableStateOf(false) }

    if (showing) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text(stringResource(R.string.a11y_disclosure_title)) },
            text = {
                Text(
                    text = stringResource(R.string.a11y_disclosure_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showing = false
                        onConsent()
                    },
                ) { Text(stringResource(R.string.a11y_disclosure_agree)) }
            },
            dismissButton = {
                TextButton(onClick = { showing = false }) {
                    Text(stringResource(R.string.a11y_disclosure_decline))
                }
            },
        )
    }

    return { showing = true }
}
