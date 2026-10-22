package com.example.niimbot_label_printer

import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.experimental.or
import kotlin.math.ceil

/** The printer answered with a 0xDB packet; [errorCode] is the first data byte. */
class PrinterErrorException(val errorCode: Int) :
    Exception("Printer reported error 0x%02X".format(errorCode))

class PrintTimeoutException(message: String) : Exception(message)

class UnsupportedCommandException(command: Byte) :
    Exception("Printer does not support command 0x%02X".format(command))

class NiimbotProtocolException(message: String) : Exception(message)

// https://github.com/AndBondStyle/niimprint/blob/main/readme.md
// Only IOException signals a broken Bluetooth connection; every other exception thrown here
// is a print/protocol failure and leaves the socket usable.
class NiimbotPrinter(private val context: Context, private val bluetoothSocket: BluetoothSocket) {

    companion object {
        private const val TAG = "NiimbotPrinter"

        const val RESPONSE_TIMEOUT_MS = 2_000L
        /** Shared budget for the endPagePrint + getPrintStatus loops after the image is sent. */
        const val PRINT_COMPLETION_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 10L

        private const val TYPE_NOT_SUPPORTED = 0x00
        private const val TYPE_PRINT_ERROR = 0xDB

        const val ERROR_COVER_OPEN = 0x01
        const val ERROR_NO_PAPER = 0x02

        // heartbeat() paper_state value meaning "no paper". Unverified on the B3S, so the
        // pre-print paper check is disabled (null) until confirmed from the logged heartbeats.
        private val NO_PAPER_STATE: Int? = null
    }

    private class Packet(val type: Int, val data: ByteArray) {
        val ok: Boolean get() = data.isNotEmpty() && data[0] != 0.toByte()
        val errorCode: Int get() = (data.firstOrNull()?.toInt() ?: 0) and 0xFF
    }

    private var rxBuffer = ByteArray(0)

    private suspend fun sendCommand(
        requestCode: Byte,
        data: ByteArray,
        responseTypes: Set<Int>,
        failOnPrinterError: Boolean = true,
    ): Packet = withContext(Dispatchers.IO) {
        drainInput(failOnPrinterError)

        val packet = createPacket(requestCode, data)
        Log.d(TAG, "-> ${packet.toHex()}")
        bluetoothSocket.outputStream.write(packet)
        bluetoothSocket.outputStream.flush()

        awaitResponse(requestCode, responseTypes, failOnPrinterError)
    }

    private suspend fun awaitResponse(requestCode: Byte, responseTypes: Set<Int>, failOnPrinterError: Boolean): Packet {
        val deadline = SystemClock.elapsedRealtime() + RESPONSE_TIMEOUT_MS
        while (true) {
            while (true) {
                val response = nextPacket() ?: break
                when {
                    response.type in responseTypes -> return response
                    response.type == TYPE_PRINT_ERROR -> {
                        if (failOnPrinterError) throw PrinterErrorException(response.errorCode)
                    }
                    response.type == TYPE_NOT_SUPPORTED -> throw UnsupportedCommandException(requestCode)
                    else -> Log.w(TAG, "Ignoring unexpected packet 0x%02X".format(response.type))
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw PrintTimeoutException("No response to command 0x%02X".format(requestCode))
            }
            if (readAvailable() == 0) delay(POLL_INTERVAL_MS)
        }
    }

    /** Appends whatever is currently buffered on the socket to [rxBuffer] without blocking. */
    private fun readAvailable(): Int {
        val input = bluetoothSocket.inputStream
        val available = input.available()
        if (available <= 0) return 0
        val buffer = ByteArray(available)
        val read = input.read(buffer, 0, available)
        if (read < 0) throw IOException("Bluetooth stream closed")
        rxBuffer += buffer.copyOfRange(0, read)
        return read
    }

    /**
     * Discards stale bytes before a new request. The printer can push a 0xDB error at any time
     * (e.g. while image rows are streaming), so those are still surfaced.
     */
    private fun drainInput(failOnPrinterError: Boolean) {
        while (readAvailable() > 0) Unit
        var error: Int? = null
        while (true) {
            val stale = nextPacket() ?: break
            if (stale.type == TYPE_PRINT_ERROR) error = stale.errorCode
        }
        rxBuffer = ByteArray(0)
        if (failOnPrinterError && error != null) throw PrinterErrorException(error)
    }

    /** Removes the next complete `55 55 <type> <len> <data> <checksum> AA AA` packet from [rxBuffer]. */
    private fun nextPacket(): Packet? {
        while (true) {
            val start = (0 until rxBuffer.size - 1).firstOrNull {
                rxBuffer[it] == 0x55.toByte() && rxBuffer[it + 1] == 0x55.toByte()
            }
            if (start == null) {
                // Keep a trailing 0x55 in case the header is split across reads.
                rxBuffer = if (rxBuffer.lastOrNull() == 0x55.toByte()) byteArrayOf(0x55) else ByteArray(0)
                return null
            }
            if (start > 0) rxBuffer = rxBuffer.copyOfRange(start, rxBuffer.size)
            if (rxBuffer.size < 4) return null

            val length = rxBuffer[3].toInt() and 0xFF
            val total = length + 7
            if (rxBuffer.size < total) return null

            val raw = rxBuffer.copyOfRange(0, total)
            if (raw[total - 2] != 0xAA.toByte() || raw[total - 1] != 0xAA.toByte()) {
                Log.w(TAG, "Bad packet framing, resyncing: ${raw.toHex()}")
                rxBuffer = rxBuffer.copyOfRange(1, rxBuffer.size)
                continue
            }
            rxBuffer = rxBuffer.copyOfRange(total, rxBuffer.size)

            val type = raw[2].toInt() and 0xFF
            val data = raw.copyOfRange(4, 4 + length)
            var checksum = type xor length
            data.forEach { checksum = checksum xor (it.toInt() and 0xFF) }
            if (checksum != (raw[4 + length].toInt() and 0xFF)) {
                Log.w(TAG, "Checksum mismatch: ${raw.toHex()}")
            }
            Log.d(TAG, "<- %02X: %s".format(type, raw.toHex()))
            return Packet(type, data)
        }
    }

    private fun createPacket(type: Byte, data: ByteArray): ByteArray {
        val packetData = ByteBuffer.allocate(data.size + 7) // Aumentamos el tamaño en 1
            .put(0x55.toByte()).put(0x55.toByte()) // Header
            .put(type)
            .put(data.size.toByte())
            .put(data)

        var checksum = type.toInt() xor data.size
        data.forEach { checksum = checksum xor it.toInt() }

        packetData.put(checksum.toByte())
            .put(0xAA.toByte()).put(0xAA.toByte()) // Footer

        return packetData.array()
    }

    /**
     * Prints one page and returns only once the printer reports it as printed.
     * On any non-connection failure the job is aborted with endPrint() so the next job starts clean.
     */
    suspend fun printBitmap(bitmap: Bitmap, density: Int = 3, labelType: Int = 1, quantity: Int = 1, rotate: Boolean = false, invertColor: Boolean = false) {
        var bitmap = bitmap
        var width:Int = bitmap.width
        var height:Int = bitmap.height
        if (rotate) {
            bitmap = rotateBitmap90Degrees(bitmap)
            width = bitmap.width
            height = bitmap.height
        }

        if(invertColor) {
            bitmap = bitmap.invert()
        }

        try {
            checkPrinterReady()
            logIfRejected("setLabelDensity", setLabelDensity(density))
            logIfRejected("setLabelType", setLabelType(labelType))
            logIfRejected("startPrint", startPrint())
            logIfRejected("startPagePrint", startPagePrint())
            logIfRejected("setDimension", setDimension(height, width))
            logIfRejected("setQuantity", setQuantity(quantity))

            withContext(Dispatchers.IO) {
                for (packet in encodeImage(bitmap)) {
                    bluetoothSocket.outputStream.write(packet)
                    bluetoothSocket.outputStream.flush()
                    drainInput(failOnPrinterError = true)
                    delay(10) // Pequeña pausa entre paquetes
                }
            }

            waitForPageCompletion(quantity)
        } catch (e: IOException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Aborting print job: ${e.message}")
            abortPrint()
            throw e
        }

        // The page is already confirmed; a missing endPrint ack shouldn't fail the job.
        try {
            endPrint()
        } catch (e: IOException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "endPrint after successful page failed: ${e.message}")
        }
    }

    private suspend fun checkPrinterReady() {
        val state = try {
            heartbeat()
        } catch (e: PrintTimeoutException) {
            Log.w(TAG, "Heartbeat before print got no answer, continuing")
            return
        } catch (e: UnsupportedCommandException) {
            Log.w(TAG, "Heartbeat not supported, continuing")
            return
        }
        Log.d(TAG, "Heartbeat before print: $state")
        if (NO_PAPER_STATE != null && state["paper_state"] == NO_PAPER_STATE) {
            throw PrinterErrorException(ERROR_NO_PAPER)
        }
    }

    private suspend fun waitForPageCompletion(quantity: Int) {
        val deadline = SystemClock.elapsedRealtime() + PRINT_COMPLETION_TIMEOUT_MS

        while (!endPagePrint()) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw PrintTimeoutException("Printer did not accept the end of page")
            }
            delay(50)
        }

        while (true) {
            val status = getPrintStatus()
            Log.d(TAG, "Print status: $status")
            if ((status["page"] ?: 0) >= quantity) return
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw PrintTimeoutException("Printer did not report the page as printed")
            }
            delay(100)
        }
    }

    private suspend fun abortPrint() {
        try {
            endPrint(failOnPrinterError = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "endPrint during abort failed: ${e.message}")
        }
    }

    private fun logIfRejected(command: String, accepted: Boolean) {
        if (!accepted) Log.w(TAG, "$command was answered with 0")
    }

    fun rotateBitmap90Degrees(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply {
            postRotate(90f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun Bitmap.rotate90Clockwise(): Bitmap {
        val matrix = Matrix()
        matrix.setRotate(90f)
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }


    private suspend fun loadImageFromAssets(imageName: String): Bitmap =
        withContext(Dispatchers.IO) {
            val inputStream = context.assets.open(imageName)
            BitmapFactory.decodeStream(inputStream)
        }

    private fun encodeImage(bitmap: Bitmap): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val invertedBitmap = bitmap; //bitmap.invert()

        for (y in 0 until invertedBitmap.height) {
            val lineData = ByteArray(ceil(invertedBitmap.width / 8.0).toInt())
            for (x in 0 until invertedBitmap.width) {
                val pixel = invertedBitmap.getPixel(x, y)
                if (pixel == 0xFF000000.toInt()) { // Black pixel
                    lineData[x / 8] = lineData[x / 8] or (1 shl (7 - x % 8)).toByte()
                }
            }

            val header = ByteBuffer.allocate(6)
                .putShort(y.toShort())
                .put(0.toByte()).put(0.toByte()).put(0.toByte()) // counts
                .put(1.toByte())
                .array()

            val packetData = header + lineData
            packets.add(createPacket(0x85.toByte(), packetData))
        }

        return packets
    }

    private fun Bitmap.invert(): Bitmap {
        val invertedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(invertedBitmap)
        val paint = android.graphics.Paint()
        val colorMatrix = android.graphics.ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(this, 0f, 0f, paint)
        return invertedBitmap
    }

    // Response types follow niimprint/NiimBlue: most commands answer with request + 1,
    // the settings/status commands with request + 0x10.

    suspend fun setLabelDensity(n: Int): Boolean {
        require(n in 1..5) { "Density must be between 1 and 5" }
        return sendCommand(0x21, byteArrayOf(n.toByte()), setOf(0x31)).ok
    }

    suspend fun setLabelType(n: Int): Boolean {
        require(n in 1..3) { "Label type must be between 1 and 3" }
        return sendCommand(0x23, byteArrayOf(n.toByte()), setOf(0x33)).ok
    }

    suspend fun startPrint(): Boolean {
        return sendCommand(0x01, byteArrayOf(1), setOf(0x02)).ok
    }

    suspend fun endPrint(failOnPrinterError: Boolean = true): Boolean {
        return sendCommand(0xF3.toByte(), byteArrayOf(1), setOf(0xF4), failOnPrinterError).ok
    }

    suspend fun startPagePrint(): Boolean {
        return sendCommand(0x03, byteArrayOf(1), setOf(0x04)).ok
    }

    suspend fun endPagePrint(): Boolean {
        return sendCommand(0xE3.toByte(), byteArrayOf(1), setOf(0xE4)).ok
    }

    suspend fun allowPrintClear(): Boolean {
        return sendCommand(0x20, byteArrayOf(1), setOf(0x30)).ok
    }

    suspend fun setDimension(width: Int, height: Int): Boolean {
        val data = ByteBuffer.allocate(4)
            .putShort(width.toShort())
            .putShort(height.toShort())
            .array()
        return sendCommand(0x13, data, setOf(0x14)).ok
    }

    suspend fun setQuantity(n: Int): Boolean {
        val data = ByteBuffer.allocate(2).putShort(n.toShort()).array()
        return sendCommand(0x15, data, setOf(0x16)).ok
    }

    suspend fun getPrintStatus(): Map<String, Int> {
        val data = sendCommand(0xA3.toByte(), byteArrayOf(1), setOf(0xB3)).data
        if (data.size < 2) throw NiimbotProtocolException("Print status too short: ${data.toHex()}")
        return mapOf(
            "page" to ByteBuffer.wrap(data, 0, 2).short.toInt(),
            "progress1" to (data.getOrNull(2)?.toInt()?.and(0xFF) ?: 0),
            "progress2" to (data.getOrNull(3)?.toInt()?.and(0xFF) ?: 0)
        )
    }

    suspend fun getInfo(key: Byte): Any {
        val data = sendCommand(0x40, byteArrayOf(key), setOf(0x40 + key)).data
        return when (key) {
            11.toByte() -> data.joinToString("") { "%02x".format(it) } // DEVICESERIAL
            9.toByte(), 12.toByte() -> ByteBuffer.wrap(data).int / 100.0 // SOFTVERSION, HARDVERSION
            else -> ByteBuffer.wrap(data).int
        }
    }

    suspend fun getRfid(): Map<String, Any>? {
        val data = sendCommand(0x1A, byteArrayOf(1), setOf(0x1B)).data

        if (data.isEmpty() || data[0] == 0.toByte()) return null

        var idx = 8
        val barcodeLen = data[idx++].toInt()
        val barcode = String(data.copyOfRange(idx, idx + barcodeLen))
        idx += barcodeLen

        val serialLen = data[idx++].toInt()
        val serial = String(data.copyOfRange(idx, idx + serialLen))
        idx += serialLen

        val totalLen = ByteBuffer.wrap(data, idx, 2).short.toInt()
        val usedLen = ByteBuffer.wrap(data, idx + 2, 2).short.toInt()
        val type = data[idx + 4]

        return mapOf(
            "uuid" to data.copyOfRange(0, 8).joinToString("") { "%02x".format(it) },
            "barcode" to barcode,
            "serial" to serial,
            "used_len" to usedLen,
            "total_len" to totalLen,
            "type" to type
        )
    }

    suspend fun heartbeat(): Map<String, Int?> {
        // Heartbeat replies come in several variants depending on the model.
        val data = sendCommand(0xDC.toByte(), byteArrayOf(1), setOf(0xDD, 0xDE, 0xDF, 0xD9)).data

        return when (data.size) {
            20 -> mapOf(
                "closing_state" to null,
                "power_level" to null,
                "paper_state" to data[18].toInt(),
                "rfid_read_state" to data[19].toInt()
            )

            13 -> mapOf(
                "closing_state" to data[9].toInt(),
                "power_level" to data[10].toInt(),
                "paper_state" to data[11].toInt(),
                "rfid_read_state" to data[12].toInt()
            )

            19 -> mapOf(
                "closing_state" to data[15].toInt(),
                "power_level" to data[16].toInt(),
                "paper_state" to data[17].toInt(),
                "rfid_read_state" to data[18].toInt()
            )

            10 -> mapOf(
                "closing_state" to data[8].toInt(),
                "power_level" to data[9].toInt(),
                "paper_state" to null,
                "rfid_read_state" to data[8].toInt()
            )

            9 -> mapOf(
                "closing_state" to data[8].toInt(),
                "power_level" to null,
                "paper_state" to null,
                "rfid_read_state" to null
            )

            else -> mapOf(
                "closing_state" to null,
                "power_level" to null,
                "paper_state" to null,
                "rfid_read_state" to null
            )
        }
    }
}

private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
