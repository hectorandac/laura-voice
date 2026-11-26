package dev.hect.lauravoice.invocation

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Status checks and deep links to the system screens invocation depends on. Nothing here changes a setting;
 * every link opens the screen and the user makes the change.
 */
object SystemScreens {
    private const val TAG = "LauraScreens"
    private const val SETTINGS = "com.android.settings"

    fun micGranted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun isAssistant(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }.getOrDefault(false)

    fun batteryExempt(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun isSamsung() = android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /** Settings → Apps → Default apps → Digital assistant app (One UI: Choose default apps → Digital assistant app). */
    fun openAssistant(ctx: Context) = open(
        ctx,
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    /**
     * One UI: Settings → Advanced features → Side button. Its activity isn't public API, so look for it among the
     * Settings app's exported activities; else Advanced features; else (AOSP) the power button gesture; else Settings.
     */
    fun openSideKey(ctx: Context) {
        val names = runCatching {
            ctx.packageManager.getPackageInfo(SETTINGS, PackageManager.GET_ACTIVITIES).activities
                ?.filter { it.exported }?.map { it.name }
        }.getOrNull().orEmpty()
        fun find(r: Regex) = names.filter { r.containsMatchIn(it) }.minByOrNull { it.length }
            ?.let { Intent().setClassName(SETTINGS, it) }
        open(
            ctx,
            *listOfNotNull(
                find(Regex("(?i)(sidekey|side_key|sidebutton|functionkey)")),
                find(Regex("(?i)(usefulfeature|advancedfeature)")),
                find(Regex("(?i)(powermenu|longpresspower|powerbutton)")),
                Intent(Settings.ACTION_SETTINGS),
            ).toTypedArray()
        )
    }

    fun openOverlay(ctx: Context) = open(
        ctx,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    )

    fun openAppInfo(ctx: Context) = open(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))

    /** The system's "Stop optimising battery usage?" dialog; App info if that isn't available. */
    @android.annotation.SuppressLint("BatteryLife")
    fun openBattery(ctx: Context) = open(
        ctx,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
    )

    private fun open(ctx: Context, vararg candidates: Intent) {
        for (i in candidates) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Log.i(TAG, "opened ${i.component ?: i.action}")
                return
            } catch (e: ActivityNotFoundException) {
            } catch (e: SecurityException) {
            }
        }
    }
}
