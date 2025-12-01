package com.example.niimbot_label_printer

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import androidx.annotation.NonNull
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID

/** NiimbotLabelPrinterPlugin */
class NiimbotLabelPrinterPlugin : FlutterPlugin, MethodCallHandler {
    private var TAG: String = "====> NiimbotLabelPrinterPlugin: "
    private lateinit var channel: MethodChannel
    private lateinit var mContext: Context
    private var state: Boolean = false

    private val myPermissionCode = 34264
    private var activeResult: Result? = null
    private var permissionGranted: Boolean = false

    private var bluetoothSocket: BluetoothSocket? = null
    private lateinit var mac: String
    private lateinit var niimbotPrinter: NiimbotPrinter

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "niimbot_label_printer")
        channel.setMethodCallHandler(this)
        this.mContext = flutterPluginBinding.applicationContext
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        val sdkversion: Int = Build.VERSION.SDK_INT

        activeResult = result
        permissionGranted = ContextCompat.checkSelfPermission(
            mContext,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        when (call.method) {
            "ispermissionbluetoothgranted" -> {
                var permission: Boolean = true
                if (sdkversion >= 31) permission = permissionGranted
                result.success(permission)
            }
            "getPlatformVersion" -> {
                val androidVersion: String = android.os.Build.VERSION.RELEASE
                result.success("Android $androidVersion")
            }
            "isBluetoothEnabled" -> {
                var state: Boolean = false
                val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) state = true
                result.success(state)
            }
            "isConnected" -> {
                if (bluetoothSocket != null) {
                    try {
                        bluetoothSocket?.outputStream?.run {
                            write(" ".toByteArray())
                            result.success(true)
                        }
                    } catch (e: Exception) {
                        result.success(false)
                        bluetoothSocket = null
                    }
                } else {
                    result.success(false)
                }
            }
            "getPairedDevices" -> {
                val lista: List<String> = dispositivosVinculados()
                result.success(lista)
            }
            "connect" -> {
                val macimpresora = call.arguments.toString()
                if (macimpresora.isNotEmpty()) {
                    mac = macimpresora
                } else {
                    result.success(false)
                    return
                }

                // Use CoroutineScope to avoid GlobalScope leaks
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                            val device = bluetoothAdapter.getRemoteDevice(mac)
                            bluetoothSocket = device.createRfcommSocketToServiceRecord(
                                UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                            )
                            bluetoothSocket?.connect()
                            withContext(Dispatchers.Main) {
                                result.success(true)
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                result.success(false)
                            }
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                        bluetoothSocket?.close()
                        bluetoothSocket = null
                        withContext(Dispatchers.Main) {
                            result.success(false)
                        }
                    }
                }
            }
            "send" -> {
                val datosImagen = call.arguments as? Map<String, Any>
                if (datosImagen == null) {
                    result.success(false)
                    return
                }

                val bytes = (datosImagen["bytes"] as? List<Int>)?.map { it.toByte() }?.toByteArray()
                val width = (datosImagen["width"] as? Int) ?: 0
                val height = (datosImagen["height"] as? Int) ?: 0
                val rotate = (datosImagen["rotate"] as? Boolean) ?: false
                val invertColor = (datosImagen["invertColor"] as? Boolean) ?: false
                val density = (datosImagen["density"] as? Int) ?: 3
                val labelType = (datosImagen["labelType"] as? Int) ?: 1

                if (bytes == null || width <= 0 || height <= 0) {
                    result.success(false)
                    return
                }

                val expectedBufferSize = width * height * 4
                if (bytes.size != expectedBufferSize) {
                    result.success(false)
                    return
                }

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))

                // Ensure socket exists - otherwise reply false and return immediately
                val socket = bluetoothSocket
                if (socket == null) {
                    result.success(false)
                    return
                }

                niimbotPrinter = NiimbotPrinter(mContext, socket)

                // Use a CoroutineScope on IO for all I/O operations
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // wake printer (runs printBitmap which handles internal init)
                        initNiimbotPrinter(niimbotPrinter)

                        // print actual bitmap
                        niimbotPrinter.printBitmap(
                            bitmap,
                            density = density,
                            labelType = labelType,
                            rotate = rotate,
                            invertColor = invertColor
                        )

                        withContext(Dispatchers.Main) {
                            result.success(true)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        // close socket on error
                        try {
                            bluetoothSocket?.close()
                        } catch (closeEx: Exception) {
                            // ignore
                        }
                        bluetoothSocket = null

                        withContext(Dispatchers.Main) {
                            result.success(false)
                        }
                    }
                }
            }
            "disconnect" -> {
                disconnect()
                result.success(true)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun dispositivosVinculados(): List<String> {
        val listItems: MutableList<String> = mutableListOf()
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
        pairedDevices?.forEach { device ->
            val deviceName = device.name
            val deviceHardwareAddress = device.address
            listItems.add("$deviceName#$deviceHardwareAddress")
        }
        return listItems
    }

    private suspend fun connect(): OutputStream? {
        return withContext(Dispatchers.IO) {
            var outputStream: OutputStream? = null
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                try {
                    val bluetoothAddress = mac
                    val bluetoothDevice = bluetoothAdapter.getRemoteDevice(bluetoothAddress)
                    val bluetoothSocket = bluetoothDevice?.createRfcommSocketToServiceRecord(
                        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                    )
                    bluetoothAdapter.cancelDiscovery()
                    bluetoothSocket?.connect()
                    if (bluetoothSocket!!.isConnected) {
                        outputStream = bluetoothSocket!!.outputStream
                        state = true
                    } else {
                        state = false
                        Log.d(TAG, "Desconectado: ")
                    }
                } catch (e: Exception) {
                    state = false
                    Log.d(TAG, "connect: ${e.message}")
                    outputStream?.close()
                }
            } else {
                state = false
                Log.d(TAG, "Problema adapter: ")
            }
            outputStream
        }
    }

    // wakes the printer by sending a 1×1 dummy bitmap using printBitmap (which performs internal init)
    private suspend fun initNiimbotPrinter(printer: NiimbotPrinter) {
        val dummy = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        printer.printBitmap(
            dummy,
            density = 3,
            labelType = 1,
            rotate = false,
            invertColor = false
        )
    }

    private fun disconnect() {
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        bluetoothSocket = null
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
}
