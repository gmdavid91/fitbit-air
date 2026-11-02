package dev.airpulse.fitbit_air.sample

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * A plain GATT heart-rate subscription, separate from FitbitAirClient, the way
 * another app on the phone (or an existing fitness app) would read it. Used to
 * test whether haptics still work while such a connection is open.
 */
@SuppressLint("MissingPermission")
class RawHeartRate(private val context: Context) {
    val samples = AtomicInteger(0)
    private var gatt: BluetoothGatt? = null

    /** Returns false when no bonded Fitbit Air exists. */
    fun start(): Boolean {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val device = manager.adapter?.bondedDevices?.firstOrNull {
            it.name?.contains("Fitbit Air", ignoreCase = true) == true
        } ?: return false
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        return true
    }

    fun stop() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val characteristic = g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT) ?: return
            val descriptor = characteristic.getDescriptor(CCCD) ?: return
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

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == HEART_RATE_MEASUREMENT) samples.incrementAndGet()
        }

        @Deprecated("Android < 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && c.uuid == HEART_RATE_MEASUREMENT) samples.incrementAndGet()
        }
    }

    private companion object {
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
