package net.qmindtech.tmap.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * In-context reminder permission gate (spec §6):
 *  - Requests POST_NOTIFICATIONS once on Android 13+ when not yet granted.
 *  - When exact alarms are denied on Android 12+, shows a rationale row with a button that opens
 *    the system Settings screen for the exact-alarm grant.
 * Decision logic lives in decideReminderPermissions() (unit-tested); this is the UI shell.
 */
@Composable
fun ReminderPermissionGate(
    canScheduleExact: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var notificationsGranted by remember { mutableStateOf(isPostNotificationsGranted(context)) }
    val exactAllowed = canScheduleExact()
    val decision = decideReminderPermissions(
        sdkInt = Build.VERSION.SDK_INT,
        postNotificationsGranted = notificationsGranted,
        canScheduleExact = exactAllowed,
    )

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }

    LaunchedEffect(decision.requestPostNotifications) {
        if (decision.requestPostNotifications && Build.VERSION.SDK_INT >= ANDROID_13) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (decision.showExactAlarmRationale) {
        Row(
            modifier = modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Allow exact alarms so reminders fire on time.")
            Button(onClick = { openExactAlarmSettings(context) }) { Text("Allow") }
        }
    }
}

private fun isPostNotificationsGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= ANDROID_13) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= ANDROID_12) {
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
