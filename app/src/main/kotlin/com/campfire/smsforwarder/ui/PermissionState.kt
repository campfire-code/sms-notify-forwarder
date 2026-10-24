package com.campfire.smsforwarder.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

data class PermissionState(
    val sms: Boolean,
    val notificationAccess: Boolean,
    val batteryIgnored: Boolean,
    val postNotifications: Boolean,
) {
    companion object {
        fun read(c: Context): PermissionState {
            val pm = c.getSystemService(PowerManager::class.java)
            return PermissionState(
                sms = ContextCompat.checkSelfPermission(c, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED,
                notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName),
                batteryIgnored = pm.isIgnoringBatteryOptimizations(c.packageName),
                postNotifications = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            )
        }

        fun notificationAccessIntent() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        fun batteryIntent(c: Context) = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${c.packageName}"),
        )

        fun batteryListIntent() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        fun appDetailsIntent(c: Context) = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${c.packageName}"),
        )
    }
}
