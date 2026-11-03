package dev.airpulse.airbuzz.air

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

enum class HrState { OFF, NO_PERMISSION, NO_BAND, CONNECTING, WAITING, LIVE }

data class HrSample(val bpm: Int, val atElapsedMillis: Long)

/**
 * Live heart rate from the band's standard Heart Rate Service (0x180D) over
 * its own GATT connection, so it runs alongside haptic commands. The band
 * only sends it with "share heart rate" turned on in Google Health.
 *
 * Reference counted: each user calls [acquire] and later [release].
 */
@SuppressLint("MissingPermission")
class HeartRateMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var users = 0
    private var gatt: BluetoothGatt? = null

    private val _state = MutableStateFlow(HrState.OFF)
    val state: StateFlow<HrState> = _state

    private val _latest = MutableStateFlow<HrSample?>(null)
    /** Last sample, cleared after [STALE_MILLIS] without data. */
    val latest: StateFlow<HrSample?> = _latest

    private val _samples = MutableSharedFlow<HrSample>(extraBufferCapacity = 64)
    val samples: SharedFlow<HrSample> = _samples

    fun acquire() {
        handler.post {
            users++
            if (users == 1) connect()
        }
    }

    fun release() {
        handler.post {
            if (users == 0) return@post
            users--
            if (users == 0) {
                handler.removeCallbacksAndMessages(null)
                disconnect()
                _state.value = HrState.OFF
                _latest.value = null
            }
        }
    }

    private fun connect() {
        if (Build.VERSION.SDK_INT >= 31 &&
            appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            _state.value = HrState.NO_PERMISSION
            return
        }
        val device = findBand()
        if (device == null) {
            _state.value = HrState.NO_BAND
            retryLater()
            return
        }
        _state.value = HrState.CONNECTING
        gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        handler.removeCallbacks(staleCheck)
        handler.postDelayed(staleCheck, STALE_MILLIS)
    }

    private fun findBand(): BluetoothDevice? = runCatching {
        appContext.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.firstOrNull {
            it.name?.contains("Fitbit Air", ignoreCase = true) == true
        }
    }.getOrNull()

    private fun disconnect() {
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
    }

    private fun retryLater() {
        handler.postDelayed({ if (users > 0) { disconnect(); connect() } }, RETRY_MILLIS)
    }

    private val staleCheck: Runnable = object : Runnable {
        override fun run() {
            if (users == 0) return
            val last = _latest.value
            if (last != null && SystemClock.elapsedRealtime() - last.atElapsedMillis > STALE_MILLIS) {
                _latest.value = null
                if (_state.value == HrState.LIVE) _state.value = HrState.WAITING
            }
            handler.postDelayed(this, 5_000)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _state.value = HrState.WAITING
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && users > 0) {
                    _state.value = HrState.CONNECTING
                    retryLater()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                val characteristic = g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT)
                val descriptor = characteristic?.getDescriptor(CCCD)
                if (characteristic == null || descriptor == null) { _state.value = HrState.WAITING; retryLater(); return@post }
                g.setCharacteristicNotification(characteristic, true)
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == HEART_RATE_MEASUREMENT) deliver(value)
        }

        @Deprecated("Android < 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33 && c.uuid == HEART_RATE_MEASUREMENT) deliver(c.value ?: return)
        }
    }

    private fun deliver(value: ByteArray) {
        val bpm = parseBpm(value) ?: return
        val sample = HrSample(bpm, SystemClock.elapsedRealtime())
        handler.post {
            if (users == 0) return@post
            _latest.value = sample
            _state.value = HrState.LIVE
            _samples.tryEmit(sample)
        }
    }

    companion object {
        private const val STALE_MILLIS = 15_000L
        private const val RETRY_MILLIS = 5_000L
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Heart Rate Measurement: flags bit 0 selects uint8 or little-endian uint16. */
        fun parseBpm(value: ByteArray): Int? {
            if (value.size < 2) return null
            val wide = value[0].toInt() and 1 != 0
            return if (wide) {
                if (value.size < 3) null else (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
            } else value[1].toInt() and 0xFF
        }
    }
}
