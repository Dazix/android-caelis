package com.dazix.caelis.ui.playstore

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dazix.caelis.R
import com.dazix.caelis.domain.playstore.InAppUpdatePolicy
import com.dazix.caelis.domain.playstore.ReviewPromptPolicy
import com.dazix.caelis.domain.playstore.UpdateAction
import com.dazix.caelis.domain.playstore.UpdateAvailability
import com.dazix.caelis.domain.playstore.UpdateCheck
import com.dazix.caelis.domain.playstore.UpdateInstall
import com.dazix.caelis.ui.AppIcons
import com.dazix.caelis.ui.MainViewModel
import com.google.android.gms.tasks.Task
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.review.ReviewManagerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.android.play.core.install.model.UpdateAvailability as PlayAvailability

/**
 * The Play Store's own update and review sheets, asked for at every return to the app and shown only
 * when [InAppUpdatePolicy] and [ReviewPromptPolicy] say so.
 *
 * Both go to the Play Store app over IPC; nothing here opens a connection. A build that was not
 * installed from Play (a debug build, a sideload) gets no update and an empty review flow, which is
 * exactly what an error here also turns into: no answer is never a reason to show anything.
 *
 * An accepted update downloads in the background. Installing it restarts the app, so that waits for
 * the "Restart" bar this composable draws, and the bar waits for any running session to end.
 */
@Composable
fun PlayStorePrompts(
    viewModel: MainViewModel,
    onHomeScreen: Boolean,
    sessionActive: Boolean,
    showRestartBar: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val updates = remember(context) { AppUpdateManagerFactory.create(context.applicationContext) }
    val reviews = remember(context) { ReviewManagerFactory.create(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val currentHome by rememberUpdatedState(onHomeScreen)
    val currentSession by rememberUpdatedState(sessionActive)

    var downloaded by remember { mutableStateOf(false) }
    // "Not now" holds for this run of the app; the next start asks again, as the download is still there.
    var restartDismissed by remember { mutableStateOf(false) }

    // Accepted or declined, the sheet's own answer adds nothing: progress comes through the listener,
    // and a declined version is not offered again either way.
    val updateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { }

    DisposableEffect(updates) {
        val listener = InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) downloaded = true
        }
        updates.registerListener(listener)
        onDispose { updates.unregisterListener(listener) }
    }

    LifecycleResumeEffect(updates, reviews) {
        val job = scope.launch {
            val today = LocalDate.now().toEpochDay()
            val state = viewModel.recordOpenForPlayPrompts(today) ?: return@launch
            val info = updates.appUpdateInfo.awaitOrNull()
            val action = InAppUpdatePolicy.decide(
                check = info?.let(PlayUpdateMapping::check),
                lastOfferedVersionCode = state.lastOfferedUpdateVersion,
                sessionActive = currentSession,
            )
            when (action) {
                is UpdateAction.Offer -> {
                    val started = runCatching {
                        updates.startUpdateFlowForResult(
                            info!!,
                            updateLauncher,
                            AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE),
                        )
                    }.getOrDefault(false)
                    if (started) viewModel.markUpdateOffered(action.versionCode)
                }
                UpdateAction.OfferRestart -> downloaded = true
                UpdateAction.None -> Unit
            }
            val askForReview = ReviewPromptPolicy.shouldRequest(
                history = state.review,
                today = today,
                installEpochDay = installEpochDay(context),
                onHomeScreen = currentHome,
                sessionActive = currentSession,
                updateOffered = action != UpdateAction.None,
            )
            if (askForReview && activity != null) {
                val reviewInfo = reviews.requestReviewFlow().awaitOrNull() ?: return@launch
                // Counted as asked before Play decides whether to show it: Play never says, and
                // asking again sooner would only spend its quota.
                viewModel.markReviewRequested(today)
                reviews.launchReviewFlow(activity, reviewInfo)
            }
        }
        onPauseOrDispose { job.cancel() }
    }

    // A session running in the camera app lives in this process; restarting into the update would end it.
    AnimatedVisibility(
        visible = downloaded && !restartDismissed && !sessionActive && showRestartBar,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        UpdateReadyBar(
            onRestart = { updates.completeUpdate() },
            onDismiss = { restartDismissed = true },
        )
    }
}

@Composable
private fun UpdateReadyBar(onRestart: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = 18.dp, end = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.update_downloaded),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRestart) {
                Text(
                    text = stringResource(R.string.update_restart),
                    color = MaterialTheme.colorScheme.inversePrimary,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = AppIcons.Clear,
                    contentDescription = stringResource(R.string.update_not_now),
                    tint = LocalContentColor.current,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Play's integer codes, read into the policy's own types. Anything unrecognised reads as nothing known. */
internal object PlayUpdateMapping {
    fun availability(code: Int): UpdateAvailability = when (code) {
        PlayAvailability.UPDATE_AVAILABLE -> UpdateAvailability.AVAILABLE
        PlayAvailability.UPDATE_NOT_AVAILABLE -> UpdateAvailability.NOT_AVAILABLE
        PlayAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> UpdateAvailability.IN_PROGRESS
        else -> UpdateAvailability.UNKNOWN
    }

    fun install(code: Int): UpdateInstall = when (code) {
        InstallStatus.PENDING -> UpdateInstall.PENDING
        InstallStatus.DOWNLOADING -> UpdateInstall.DOWNLOADING
        InstallStatus.DOWNLOADED -> UpdateInstall.DOWNLOADED
        InstallStatus.INSTALLING -> UpdateInstall.INSTALLING
        InstallStatus.INSTALLED -> UpdateInstall.INSTALLED
        InstallStatus.FAILED -> UpdateInstall.FAILED
        InstallStatus.CANCELED -> UpdateInstall.CANCELED
        else -> UpdateInstall.NONE
    }

    fun check(info: AppUpdateInfo): UpdateCheck = UpdateCheck(
        availability = availability(info.updateAvailability()),
        install = install(info.installStatus()),
        availableVersionCode = info.availableVersionCode(),
        flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
    )
}

/**
 * The app's page in the Play Store app, or on the web when there is no Play Store. This is the
 * reliable way to rate: the in-app review sheet is rationed by Play and cannot be opened on demand.
 */
fun openPlayStoreListing(context: Context): Boolean {
    val pkg = context.packageName
    val app = Intent(Intent.ACTION_VIEW, "market://details?id=$pkg".toUri())
        .setPackage(PLAY_STORE_PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$pkg".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    for (intent in listOf(app, web)) {
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Try the next one.
        }
    }
    return false
}

const val PLAY_STORE_PACKAGE = "com.android.vending"

private fun installEpochDay(context: Context): Long? = runCatching {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0)
    }
    Instant.ofEpochMilli(info.firstInstallTime).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
}.getOrNull()

/** A Play task's result, or null for a failure or a cancellation; the callbacks run on the main thread. */
private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        cont.resume(if (task.isSuccessful) task.result else null)
    }
}
