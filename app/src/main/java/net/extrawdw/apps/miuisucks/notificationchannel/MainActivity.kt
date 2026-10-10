package net.extrawdw.apps.miuisucks.notificationchannel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RepairTheme { RepairScreen() } }
    }

    @Composable
    private fun RepairScreen() {
        val state = RepairState
        val permissionError = stringResource(R.string.error_notification_permission)
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRepair() else state.status = permissionError
        }
        RepairContent(
            state = RepairUiState(
                status = state.status, detail = state.detail, apps = state.apps,
                selected = state.selected, plans = state.plans, connected = state.connected,
                busy = state.busy, running = state.running, recovery = state.recovery,
                needsGlobalLockscreen = state.errorRes == R.string.error_global_lockscreen,
            ),
            actions = RepairActions(
                connect = state::connect, scan = state::scan, stop = state::stop,
                recover = state::recover, preview = state::preview,
                openGlobalLockscreen = {
                    try {
                        startActivity(Intent().setClassName("com.miui.notification",
                            "miui.notification.management.activity.LockScreenNotificationsActivity"))
                    } catch (e: Exception) { state.error(e) }
                },
                select = { state.selected = it },
                back = { state.plans = emptyList() },
                start = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else startRepair()
                },
            ),
        )
    }

    private fun startRepair() {
        startForegroundService(Intent(this, RepairRunner::class.java))
    }
}
