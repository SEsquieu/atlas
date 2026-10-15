package com.grinningfrog.atlas.workspace.application

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Android owns the radio. The document receives only per-instance opaque handles and bounded JSON. */
@SuppressLint("MissingPermission")
internal class AndroidWorkspaceBle(private val context: Context) : WorkspaceBleAdapter {
    companion object {
        fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private data class Device(val device: BluetoothDevice, var info: JSONObject)
    private val devices = linkedMapOf<String, Device>()
    private val characteristics = linkedMapOf<String, BluetoothGattCharacteristic>()
    private val notifications = ArrayDeque<JSONObject>()
    private var dropped = 0L
    private var lastFailure: JSONObject? = null
    private fun recordFailure(phase: String, code: Int) { lastFailure = JSONObject().put("phase", phase).put("platform_code", code).put("at_ms", System.currentTimeMillis()) }
    private var gatt: BluetoothGatt? = null
    private var connectedLabel: String? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var scanFailure: Int? = null
    private var waiter: CompletableDeferred<JSONObject>? = null
    private var waitingKind: String? = null
    private var waitingCharacteristic: BluetoothGattCharacteristic? = null
    override fun permitted() = permissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    override fun status(): JSONObject = JSONObject().put("installed", context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
        .put("os_permission_granted", permitted()).put("platform_enabled", runCatching { permitted() && adapter?.isEnabled == true }.getOrDefault(false))
        .put("last_failure", lastFailure ?: JSONObject.NULL).put("connected", connectedLabel != null).put("device_label", connectedLabel ?: JSONObject.NULL).put("scanning", scanCallback != null)
    private fun available() {
        require(context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && adapter != null) { "UNSUPPORTED_CAPABILITY" }
        require(permitted()) { "OS_PERMISSION_REQUIRED" }
        require(adapter!!.isEnabled) { "PLATFORM_DISABLED" }
    }
    override suspend fun scan(): JSONArray {
        available()
        // Retain handles while a connection is active; scans don't silently change its scope.
        if (gatt == null) devices.clear()
        scanFailure = null
        val owner = requireNotNull(adapter!!.bluetoothLeScanner) { "PLATFORM_DISABLED" }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { handler.post {
                if (scanCallback !== this) return@post
                val existing = devices.entries.firstOrNull { it.value.device.address == result.device.address }?.key
                if (existing == null && devices.size >= 64) return@post
                val id = existing ?: WorkspaceBleContract.id()
                val record = result.scanRecord
                val name = record?.deviceName?.take(100) ?: "Unnamed BLE device"
                val info = JSONObject().put("device_id", id).put("name", name).put("rssi", result.rssi)
                    .put("service_uuids", JSONArray(record?.serviceUuids?.take(8)?.map { it.uuid.toString() }.orEmpty()))
                devices[id] = Device(result.device, info)
            } }
            override fun onScanFailed(errorCode: Int) { handler.post { if (scanCallback === this) { scanFailure = errorCode; recordFailure("scan", errorCode) } } }
        }
        scanner = owner; scanCallback = callback
        try {
            owner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            delay(5_000)
            require(scanFailure == null) { "BLE_FAILED" }
            available()
            return JSONArray(devices.values.map { JSONObject(it.info.toString()) })
        } finally {
            runCatching { owner.stopScan(callback) }
            if (scanCallback === callback) { scanCallback = null; scanner = null }
        }
    }
    override fun deviceLabel(id: String): String = (devices[id] ?: error("DEVICE_UNAVAILABLE")).info.getString("name")
    private fun endConnection() {
        val old = gatt; gatt = null; connectedLabel = null
        characteristics.clear(); notifications.clear(); dropped = 0
        waiter?.completeExceptionally(IllegalStateException("DEVICE_UNAVAILABLE")); waiter = null
        waitingKind = null; waitingCharacteristic = null
        if (old != null) { runCatching { old.disconnect() }; runCatching { old.close() } }
    }
    private suspend fun awaitResult(kind: String, characteristic: BluetoothGattCharacteristic? = null, start: () -> Boolean): JSONObject {
        require(waiter == null) { "BLE_BUSY" }
        val pending = CompletableDeferred<JSONObject>()
        waiter = pending; waitingKind = kind; waitingCharacteristic = characteristic
        try {
            require(start()) { "BLE_FAILED" }
            return withTimeout(12_000) { pending.await() }
        } finally { if (waiter === pending) { waiter = null; waitingKind = null; waitingCharacteristic = null } }
    }
    private fun complete(owner: BluetoothGatt, kind: String, characteristic: BluetoothGattCharacteristic?, status: Int, value: ByteArray? = null) {
        handler.post {
            if (gatt !== owner || waitingKind != kind || (characteristic != null && waitingCharacteristic !== characteristic)) return@post
            if (status == BluetoothGatt.GATT_SUCCESS) waiter?.complete(JSONObject().put("gatt_status", status).apply {
                if (value != null) put("hex", value.take(512).joinToString("") { "%02x".format(it.toInt() and 255) }).put("truncated", value.size > 512)
            }) else waiter?.completeExceptionally(IllegalStateException("BLE_FAILED"))
        }
    }
    private val callbacks = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(owner: BluetoothGatt, status: Int, newState: Int) { handler.post {
            if (gatt !== owner) return@post
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) { if (status != BluetoothGatt.GATT_SUCCESS) recordFailure("connection", status); endConnection(); return@post }
            if (newState == BluetoothProfile.STATE_CONNECTED && !runCatching { owner.discoverServices() }.getOrDefault(false)) endConnection()
        } }
        override fun onServicesDiscovered(owner: BluetoothGatt, status: Int) = complete(owner, "connect", null, status)
        @Deprecated("Legacy callback")
        override fun onCharacteristicRead(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { if (Build.VERSION.SDK_INT < 33) complete(owner, "read", characteristic, status, characteristic.value?.clone() ?: byteArrayOf()) }
        override fun onCharacteristicRead(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) = complete(owner, "read", characteristic, status, value.clone())
        override fun onCharacteristicWrite(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = complete(owner, "write", characteristic, status)
        override fun onDescriptorWrite(owner: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = complete(owner, "subscribe", descriptor.characteristic, status)
        private fun notification(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { handler.post {
            if (gatt !== owner) return@post
            val id = characteristics.entries.firstOrNull { it.value === characteristic }?.key ?: return@post
            if (notifications.size >= 32) { notifications.removeFirst(); dropped++ }
            notifications.addLast(JSONObject().put("characteristic_id", id).put("hex", value.take(512).joinToString("") { "%02x".format(it.toInt() and 255) })
                .put("truncated", value.size > 512).put("received_at_ms", System.currentTimeMillis()))
        } }
        @Deprecated("Legacy callback")
        override fun onCharacteristicChanged(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { if (Build.VERSION.SDK_INT < 33) notification(owner, characteristic, characteristic.value?.clone() ?: byteArrayOf()) }
        override fun onCharacteristicChanged(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) = notification(owner, characteristic, value.clone())
    }
    override suspend fun connect(id: String): JSONObject {
        available()
        val device = devices[id] ?: error("DEVICE_UNAVAILABLE")
        endConnection()
        try {
            awaitResult("connect") {
                gatt = device.device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE)
                gatt != null
            }
            available()
            val connection = gatt ?: error("DEVICE_UNAVAILABLE")
            for (service in connection.services.take(32)) for (characteristic in service.characteristics) {
                if (characteristics.size >= 128) break
                characteristics[WorkspaceBleContract.id()] = characteristic
            }
            connectedLabel = device.info.getString("name")
            return JSONObject().put("connected", true).put("name", connectedLabel)
        } catch (failure: Exception) { endConnection(); throw failure }
    }
    override fun services(): JSONArray {
        available(); require(gatt != null && connectedLabel != null) { "DEVICE_UNAVAILABLE" }
        return JSONArray(characteristics.entries.groupBy { it.value.service }.map { (service, entries) ->
            JSONObject().put("uuid", service.uuid.toString()).put("characteristics", JSONArray(entries.map { (id, characteristic) ->
                JSONObject().put("characteristic_id", id).put("uuid", characteristic.uuid.toString()).put("properties", characteristic.properties)
                    .put("readable", characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0)
                    .put("writable", characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                    .put("notifiable", characteristic.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
            }))
        })
    }
    private fun characteristic(id: String, property: Int): BluetoothGattCharacteristic {
        available(); require(gatt != null && connectedLabel != null) { "DEVICE_UNAVAILABLE" }
        val value = characteristics[id] ?: error("DEVICE_UNAVAILABLE")
        require(value.properties and property != 0) { "UNSUPPORTED_CAPABILITY" }
        return value
    }
    override suspend fun read(id: String): JSONObject {
        val value = characteristic(id, BluetoothGattCharacteristic.PROPERTY_READ)
        val owner = gatt!!
        return awaitResult("read", value) { owner.readCharacteristic(value) }
    }
    @Suppress("DEPRECATION")
    override suspend fun write(id: String, hex: String): JSONObject {
        val value = characteristic(id, BluetoothGattCharacteristic.PROPERTY_WRITE)
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val owner = gatt!!
        return awaitResult("write", value) {
            if (Build.VERSION.SDK_INT >= 33) owner.writeCharacteristic(value, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else { value.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; value.value = bytes; owner.writeCharacteristic(value) }
        }.put("acknowledged", true)
    }
    @Suppress("DEPRECATION")
    override suspend fun subscribe(id: String, enabled: Boolean): JSONObject {
        val value = characteristic(id, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)
        val descriptor = value.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) ?: error("UNSUPPORTED_CAPABILITY")
        val bytes = if (!enabled) BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE else if (value.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val owner = gatt!!
        require(owner.setCharacteristicNotification(value, enabled)) { "BLE_FAILED" }
        return awaitResult("subscribe", value) {
            if (Build.VERSION.SDK_INT >= 33) owner.writeDescriptor(descriptor, bytes) == BluetoothStatusCodes.SUCCESS
            else { descriptor.value = bytes; owner.writeDescriptor(descriptor) }
        }.put("enabled", enabled)
    }
    override fun events(): JSONObject {
        available(); require(gatt != null && connectedLabel != null) { "DEVICE_UNAVAILABLE" }
        val result = JSONObject().put("events", JSONArray(notifications.toList())).put("dropped", dropped)
        notifications.clear(); dropped = 0
        return result
    }
    override fun close() {
        scanCallback?.let { callback -> runCatching { scanner?.stopScan(callback) } }
        scanner = null; scanCallback = null
        endConnection(); devices.clear()
    }
}
