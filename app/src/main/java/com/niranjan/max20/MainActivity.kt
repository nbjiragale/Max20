package com.niranjan.max20

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niranjan.max20.receiver.Max20DeviceAdminReceiver
import com.niranjan.max20.service.LockdownAccessibilityService
import com.niranjan.max20.service.TimeEnforcerService
import com.niranjan.max20.ui.theme.Max20Theme
import com.niranjan.max20.util.VivoOptimizationHelper

/**
 * MainActivity — the onboarding / control surface and the bootstrap entry point.
 *
 * This is the LAUNCHER activity. Without it actually wiring up the required
 * permissions and starting [TimeEnforcerService], the rest of the app is
 * unreachable: TimeEnforcerService is only started from LockdownActivity, and
 * LockdownActivity is only reachable once the enforcer has driven the device into
 * a LOCKDOWN phase (or once it holds ROLE_HOME). MainActivity breaks that
 * chicken-and-egg by guiding the user through setup and kicking off the cycle.
 *
 * The screen recomputes every permission's state on each onResume() (via
 * [refreshTick]) so returning from a system settings page reflects the new grant
 * immediately.
 */
class MainActivity : ComponentActivity() {

    private var refreshTick by mutableIntStateOf(0)

    // POST_NOTIFICATIONS is a runtime permission on API 33+.
    private val notificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshTick++
        }

    // READ_PHONE_STATE is a runtime permission used to detect call state so the
    // lockdown can step aside during calls.
    private val phonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshTick++
        }

    // Generic launcher for settings/role screens that return a result (e.g. roles).
    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshTick++
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Max20Theme {
                // Reading refreshTick here ties recomposition to onResume refreshes.
                OnboardingScreen(activity = this, refreshTick = refreshTick)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick++
    }

    // ── Permission / capability state ───────────────────────────────────────

    fun areNotificationsGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun isPhoneStateGranted(): Boolean =
        checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun isOverlayGranted(): Boolean = Settings.canDrawOverlays(this)

    fun isBatteryUnrestricted(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()
        } else {
            true
        }

    fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, LockdownAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isDeviceAdminActive(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, Max20DeviceAdminReceiver::class.java))
    }

    fun isRoleHeld(role: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(role) && rm.isRoleHeld(role)
    }

    /** The minimum set of grants required for the enforcer to function at all. */
    fun coreReady(): Boolean =
        areNotificationsGranted() && isOverlayGranted() && isAccessibilityEnabled()

    // ── Permission / capability requests ────────────────────────────────────

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationsPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun requestPhoneState() {
        phonePermissionLauncher.launch(android.Manifest.permission.READ_PHONE_STATE)
    }

    fun requestOverlay() {
        if (VivoOptimizationHelper.isVivoDevice) {
            VivoOptimizationHelper.openOverlayPermissionSettings(this)
        } else {
            launchSafely(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    fun requestBattery() {
        if (VivoOptimizationHelper.isVivoDevice) {
            VivoOptimizationHelper.openBatteryOptimizationSettings(this)
        } else {
            launchSafely(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    fun requestExactAlarms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            launchSafely(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    fun requestAccessibility() {
        launchSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun requestUsageAccess() {
        launchSafely(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                ComponentName(this@MainActivity, Max20DeviceAdminReceiver::class.java)
            )
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_description)
            )
        }
        launchSafely(intent)
    }

    fun requestRole(role: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val rm = getSystemService(RoleManager::class.java) ?: return
        if (!rm.isRoleAvailable(role) || rm.isRoleHeld(role)) return
        runCatching { settingsLauncher.launch(rm.createRequestRoleIntent(role)) }
            .onFailure { Log.e("Max20:Onboarding", "Failed to request role $role: ${it.message}") }
    }

    fun startEnforcer() {
        runCatching {
            startForegroundService(Intent(this, TimeEnforcerService::class.java))
            Toast.makeText(this, getString(R.string.onboarding_started), Toast.LENGTH_LONG).show()
        }.onFailure { ex ->
            Log.e("Max20:Onboarding", "Failed to start TimeEnforcerService: ${ex.message}")
            Toast.makeText(this, "Could not start: ${ex.message}", Toast.LENGTH_LONG).show()
        }
        refreshTick++
    }

    private fun launchSafely(intent: Intent) {
        runCatching { settingsLauncher.launch(intent) }
            .onFailure {
                Log.e("Max20:Onboarding", "No activity for $intent: ${it.message}")
                Toast.makeText(this, "Setting unavailable on this device", Toast.LENGTH_SHORT).show()
            }
    }
}

// ── Compose UI ──────────────────────────────────────────────────────────────

private data class SetupItem(
    val title: String,
    val description: String,
    val granted: Boolean,
    val required: Boolean,
    val onAction: () -> Unit,
)

@Composable
private fun OnboardingScreen(activity: MainActivity, refreshTick: Int) {
    // refreshTick is an explicit parameter: when MainActivity.onResume() increments it,
    // this composable recomposes and every permission state below is re-evaluated.
    key(refreshTick) {
        OnboardingContent(activity)
    }
}

@Composable
private fun OnboardingContent(activity: MainActivity) {
    val items = buildList {
        add(
            SetupItem(
                title = "Notifications",
                description = "Required to show the persistent 20/20 timer.",
                granted = activity.areNotificationsGranted(),
                required = true,
                onAction = activity::requestNotifications,
            )
        )
        add(
            SetupItem(
                title = "Display over other apps",
                description = "Lets the lockdown shield cover the screen during rest periods.",
                granted = activity.isOverlayGranted(),
                required = true,
                onAction = activity::requestOverlay,
            )
        )
        add(
            SetupItem(
                title = "Accessibility service",
                description = "Detects and blocks other apps the instant they open during lockdown.",
                granted = activity.isAccessibilityEnabled(),
                required = true,
                onAction = activity::requestAccessibility,
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(
                SetupItem(
                    title = "Exact alarms",
                    description = "Keeps phase transitions punctual even in Doze.",
                    granted = activity.canScheduleExactAlarms(),
                    required = true,
                    onAction = activity::requestExactAlarms,
                )
            )
        }
        add(
            SetupItem(
                title = "Unrestricted battery",
                description = "Stops the system from killing the timer when the screen is off.",
                granted = activity.isBatteryUnrestricted(),
                required = false,
                onAction = activity::requestBattery,
            )
        )
        add(
            SetupItem(
                title = "Device admin",
                description = "Blocks uninstalling the app during active lockdown.",
                granted = activity.isDeviceAdminActive(),
                required = false,
                onAction = activity::requestDeviceAdmin,
            )
        )
        add(
            SetupItem(
                title = "Usage access",
                description = "Supplemental foreground-app detection layer.",
                granted = activity.hasUsageAccess(),
                required = false,
                onAction = activity::requestUsageAccess,
            )
        )
        add(
            SetupItem(
                title = "Phone access (pause for calls)",
                description = "Detects calls so the lockdown steps aside and the phone stays usable.",
                granted = activity.isPhoneStateGranted(),
                required = false,
                onAction = activity::requestPhoneState,
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(
                SetupItem(
                    title = "Default home app",
                    description = "Routes the Home button back to the lockdown screen.",
                    granted = activity.isRoleHeld(RoleManager.ROLE_HOME),
                    required = false,
                    onAction = { activity.requestRole(RoleManager.ROLE_HOME) },
                )
            )
        }
    }

    val coreReady = activity.coreReady()

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Max20 Setup",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Grant the permissions below, then start the 20-minutes-on / " +
                    "20-minutes-rest cycle.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            items.forEach { item ->
                SetupRow(item)
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { activity.startEnforcer() },
                enabled = coreReady,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (coreReady) "Start 20/20 Rule" else "Grant required permissions first")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SetupRow(item: SetupItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (item.granted) "✓" else "✗",
                        color = if (item.granted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = item.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    if (item.required) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "required",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.description,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (item.granted) {
                OutlinedButton(onClick = item.onAction) { Text("Recheck") }
            } else {
                Button(onClick = item.onAction) { Text("Grant") }
            }
        }
    }
}
