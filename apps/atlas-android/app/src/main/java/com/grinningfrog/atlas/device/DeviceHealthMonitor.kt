package com.grinningfrog.atlas.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.grinningfrog.atlas.model.DeviceHealth

class DeviceHealthMonitor(private val context: Context, private val motion: MotionMonitor) {
    fun snapshot(): DeviceHealth {
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return DeviceHealth(
            batteryPercent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 },
            charging = battery?.isCharging == true,
            thermalStatus = if (Build.VERSION.SDK_INT >= 29) thermalName(power?.currentThermalStatus) else "unknown",
            network = networkName(),
            motion = motion.state.value,
        )
    }

    private fun networkName(): String {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "offline"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private fun thermalName(status: Int?): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> "nominal"
        PowerManager.THERMAL_STATUS_MODERATE -> "warm"
        PowerManager.THERMAL_STATUS_SEVERE -> "hot"
        PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "throttled"
        else -> "unknown"
    }
}
