package cc.buessow.mtpcommander

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A small SendObjectInfo/SendObject path for Garmin devices rejected by Android's MtpDevice. */
internal object RawMtpUploader {
    private const val COMMAND = 1
    private const val DATA = 2
    private const val RESPONSE = 3
    private const val OPEN_SESSION = 0x1002
    private const val CLOSE_SESSION = 0x1003
    private const val GET_STORAGE_IDS = 0x1004
    private const val GET_OBJECT_HANDLES = 0x1007
    private const val GET_OBJECT_INFO = 0x1008
    private const val DELETE_OBJECT = 0x100b
    private const val SEND_OBJECT_INFO = 0x100c
    private const val SEND_OBJECT = 0x100d
    private const val OK = 0x2001
    private const val SESSION_ALREADY_OPEN = 0x201e
    private const val INVALID_DATASET = 0xa806
    private const val TIMEOUT_MS = 15_000
    private const val DRAIN_TIMEOUT_MS = 300
    private const val MAX_TRANSFER = 16 * 1024
    // The device may only reply to SendObject after committing a large file to flash.
    private const val SEND_OBJECT_TIMEOUT_MS = 180_000

    /**
     * One raw MTP session. Keep it open for every file of a batch: each reconnect is another chance for
     * the watch, or Android's MtpDocumentsProvider grabbing it in between, to get confused.
     */
    class Session private constructor(private val connection: UsbDeviceConnection,
        private val usbInterface: UsbInterface, private val owned: Boolean) : Closeable {
        constructor(manager: UsbManager, device: UsbDevice) : this(
            manager.openDevice(device) ?: error("Could not open USB device for upload"), device.mtpInterface(), true)

        companion object {
            /**
             * Borrows the connection of an open MtpDevice and its MTP session, so the watch is never released.
             * Android's MtpDocumentsProvider polls the watch every few seconds and grabs it in any gap between
             * closing one connection and opening the next, leaving it unable to answer our OpenSession.
             */
            fun shared(connection: UsbDeviceConnection, device: UsbDevice) =
                Session(connection, device.mtpInterface(), false)
        }

        private val transport: Transport
        // A borrowed session continues after MtpDevice's own transactions; stay clear of its low IDs.
        private var transaction = if (owned) 1 else 0x10000
        private var storageIds: Set<Int>? = null
        // Names in each destination folder, keyed by storage and parent, so each folder is listed only once.
        private val names = mutableMapOf<Pair<Int, Int>, MutableSet<String>>()

        init {
            try {
                if (owned) check(connection.claimInterface(usbInterface, true)) { "Could not claim the MTP USB interface" }
                val input = usbInterface.bulkEndpoint(UsbConstants.USB_DIR_IN)
                val output = usbInterface.bulkEndpoint(UsbConstants.USB_DIR_OUT)
                transport = Transport(connection, input, output)
                // Android's MtpDocumentsProvider also opens the watch; claiming the interface can interrupt
                // one of its transactions, and the watch refuses commands until that data has been read.
                transport.drain()
                // A borrowed session is already open.
                if (owned) {
                    Log.i("AndroidMTP", "Raw MTP OpenSession")
                    val open = try {
                        transport.command(OPEN_SESSION, 0, 1)
                    } catch (firstError: Exception) {
                        Log.w("AndroidMTP", "Raw MTP OpenSession failed; recovering USB transport", firstError)
                        recoverTransport(connection, usbInterface, input, output)
                        transport.drain()
                        transport.command(OPEN_SESSION, 0, 1)
                    }
                    // Like Android's MtpDevice, don't insist on OpenSession: a reply left over from another host's
                    // transaction (e.g. 0x2005) may arrive here, and the device still accepts the following operations.
                    if (open.code != OK && open.code != SESSION_ALREADY_OPEN)
                        Log.w("AndroidMTP", "MTP OpenSession returned ${open.code.hex()}; continuing")
                }
            } catch (e: Exception) {
                if (owned) {
                    connection.releaseInterface(usbInterface)
                    connection.close()
                }
                throw e
            }
        }

        /** Returns the handle of [folders] below the storage root, or -1 (root) when empty. */
        fun resolve(storageId: Int, folders: List<String>): Int {
            var parent = -1
            for (folderName in folders) {
                val handles = transport.commandForData(GET_OBJECT_HANDLES, transaction++,
                    storageId, 0, parent).intArray()
                parent = handles.firstOrNull { candidate ->
                    val info = transport.commandForData(GET_OBJECT_INFO, transaction++, candidate)
                        .objectNameAndFormat()
                    info.first == folderName && info.second == MtpConstants.FORMAT_ASSOCIATION
                } ?: error("MTP folder $folderName is unavailable")
            }
            return parent
        }

        fun upload(storageId: Int, parent: Int, file: File,
            onProgress: (Long, Long) -> Unit = { _, _ -> }): Int {
            val started = System.currentTimeMillis()
            fun elapsed() = "${System.currentTimeMillis() - started} ms"
            val ids = storageIds ?: transport.commandForData(GET_STORAGE_IDS, transaction++).intArray().toSet()
                .also { storageIds = it }
            check(storageId in ids) { "MTP storage $storageId is unavailable: $ids" }
            val existing = names.getOrPut(storageId to parent) {
                transport.commandForData(GET_OBJECT_HANDLES, transaction++, storageId, 0, parent).intArray()
                    .mapTo(mutableSetOf()) {
                        transport.commandForData(GET_OBJECT_INFO, transaction++, it).objectNameAndFormat().first
                    }.also { Log.i("AndroidMTP", "Raw MTP listed ${it.size} destination objects in ${elapsed()}") }
            }
            check(file.name !in existing) { "Destination already contains ${file.name}" }
            val size = file.length()
            check(size <= 0xfffffff3L) { "Raw MTP upload currently supports files smaller than 4 GB" }
            var handle = -1
            for (wideSize in listOf(false, true)) {
                val response = transport.commandWithData(SEND_OBJECT_INFO, transaction++,
                    objectInfo(storageId, parent, file.name, size, wideSize), storageId, parent)
                if (response.code == OK) {
                    handle = response.parameters.getOrNull(2) ?: error("MTP did not return a new file handle")
                    break
                }
                if (response.code != INVALID_DATASET || wideSize)
                    error("MTP SendObjectInfo returned ${response.code.hex()}")
            }
            check(handle >= 0) { "MTP did not create the file" }
            val sent = transport.commandWithFile(SEND_OBJECT, transaction++, file, onProgress)
            if (sent.code != OK) {
                runCatching { transport.command(DELETE_OBJECT, transaction++, handle) }
                error("MTP SendObject returned ${sent.code.hex()}")
            }
            existing += file.name
            Log.i("AndroidMTP", "Raw MTP uploaded ${file.name} ($size bytes) in ${elapsed()}")
            return handle
        }

        /** Forgets [name] after it was deleted through another session, so it can be uploaded again. */
        fun forget(storageId: Int, parent: Int, name: String) {
            names[storageId to parent]?.remove(name)
        }

        override fun close() {
            if (!owned) return
            runCatching { transport.command(CLOSE_SESSION, transaction) }
            connection.releaseInterface(usbInterface)
            connection.close()
        }
    }

    private fun UsbDevice.mtpInterface(): UsbInterface =
        (0 until interfaceCount).map(::getInterface).firstOrNull { intf ->
            intf.name?.contains("MTP", ignoreCase = true) == true ||
                intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE
        } ?: error("MTP USB interface is unavailable")

    private fun UsbInterface.bulkEndpoint(direction: Int): UsbEndpoint =
        (0 until endpointCount).map(::getEndpoint).firstOrNull {
            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == direction
        } ?: error("MTP bulk endpoint is unavailable")

    private fun recoverTransport(connection: UsbDeviceConnection, usbInterface: UsbInterface,
        input: UsbEndpoint, output: UsbEndpoint) {
        val status = ByteArray(4)
        val statusCount = connection.controlTransfer(0xa1, 0x67, 0, usbInterface.id,
            status, status.size, 3_000)
        val statusCode = if (statusCount == 4)
            (status[2].toInt() and 0xff) or ((status[3].toInt() and 0xff) shl 8) else -1
        Log.i("AndroidMTP", "MTP GetDeviceStatus returned $statusCount, code ${statusCode.hex()}")
        val reset = connection.controlTransfer(0x21, 0x66, 0, usbInterface.id,
            null, 0, 3_000)
        Log.i("AndroidMTP", "MTP class reset returned $reset")
        if (reset >= 0) Thread.sleep(500)
        for (endpoint in listOf(input, output)) {
            val cleared = connection.controlTransfer(0x02, 1, 0, endpoint.address,
                null, 0, 3_000)
            Log.i("AndroidMTP", "MTP clear halt ${endpoint.address.hex()} returned $cleared")
        }
    }

    private fun objectInfo(storageId: Int, parent: Int, name: String, size: Long,
        wideSize: Boolean): ByteArray {
        val nameBytes = mtpString(name)
        val data = ByteBuffer.allocate(52 + (if (wideSize) 4 else 0) + nameBytes.size + 3)
            .order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(storageId)
        data.putShort(MtpConstants.FORMAT_UNDEFINED.toShort())
        data.putShort(0)
        if (wideSize) data.putLong(size) else data.putInt(size.toInt())
        data.putShort(0)
        repeat(6) { data.putInt(0) }
        data.putInt(parent)
        data.putShort(0)
        data.putInt(0)
        data.putInt(0)
        data.put(nameBytes)
        data.put(0) // DateCreated
        data.put(0) // DateModified
        data.put(0) // Keywords
        return data.array()
    }

    private fun mtpString(value: String): ByteArray {
        check(value.length < 255) { "MTP filename is too long" }
        val data = ByteBuffer.allocate(1 + (value.length + 1) * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put((value.length + 1).toByte())
        value.forEach { data.putChar(it) }
        data.putChar('\u0000')
        return data.array()
    }

    private fun Int.hex() = "0x${toString(16)}"

    private fun ByteArray.intArray(): IntArray {
        val data = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        check(data.remaining() >= 4) { "Short MTP integer array" }
        val count = data.int
        check(count >= 0 && count <= data.remaining() / 4) { "Invalid MTP integer array" }
        return IntArray(count) { data.int }
    }

    private fun ByteArray.objectNameAndFormat(): Pair<String, Int> {
        check(size >= 53) { "Short MTP object info" }
        val data = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        val format = data.getShort(4).toInt() and 0xffff
        data.position(52)
        val length = data.get().toInt() and 0xff
        check(length > 0 && data.remaining() >= length * 2) { "Invalid MTP object name" }
        val name = buildString { repeat(length - 1) { append(data.char) } }
        return name to format
    }

    private class Transport(private val connection: UsbDeviceConnection,
        private val input: UsbEndpoint, private val output: UsbEndpoint) {
        data class Reply(val code: Int, val parameters: List<Int>)

        fun command(code: Int, transaction: Int, vararg parameters: Int): Reply {
            val packet = header(12 + parameters.size * 4, COMMAND, code, transaction,
                12 + parameters.size * 4)
            parameters.forEach(packet::putInt)
            write(packet.array())
            return response(transaction)
        }

        fun commandWithData(code: Int, transaction: Int, data: ByteArray,
            vararg parameters: Int): Reply {
            val request = header(12 + parameters.size * 4, COMMAND, code, transaction,
                12 + parameters.size * 4)
            parameters.forEach(request::putInt)
            write(request.array())
            write(header(12 + data.size, DATA, code, transaction).array())
            write(data)
            endTransfer(data.size.toLong())
            return response(transaction)
        }

        fun commandForData(code: Int, transaction: Int, vararg parameters: Int): ByteArray {
            val request = header(12 + parameters.size * 4, COMMAND, code, transaction,
                12 + parameters.size * 4)
            parameters.forEach(request::putInt)
            write(request.array())
            val (type, returnedCode, returnedTransaction, bytes) = readContainer()
            check(type == DATA && returnedCode == code && returnedTransaction == transaction) {
                "MTP $code returned type=$type code=${returnedCode.hex()}"
            }
            val result = response(transaction)
            check(result.code == OK) { "MTP $code returned ${result.code.hex()}" }
            return bytes
        }

        fun commandWithFile(code: Int, transaction: Int, file: File, onProgress: (Long, Long) -> Unit): Reply {
            val request = header(12, COMMAND, code, transaction)
            write(request.array())
            write(header((12 + file.length()).toInt(), DATA, code, transaction).array())
            file.inputStream().use { stream ->
                val buffer = ByteArray(256 * 1024)
                var remaining = file.length()
                while (remaining > 0) {
                    val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                    var read = 0
                    while (read < wanted) {
                        val count = stream.read(buffer, read, wanted - read)
                        check(count > 0) { "Source file ended during MTP upload" }
                        read += count
                    }
                    write(buffer, read)
                    remaining -= read
                    onProgress(file.length() - remaining, file.length())
                }
            }
            endTransfer(file.length())
            return response(transaction, SEND_OBJECT_TIMEOUT_MS)
        }

        // Garmin watches never answer a data phase whose 12-byte header shares a USB transfer with the payload
        // (Android's MtpDevice calls this FIRST_PACKET_ONLY_HEADER). A payload filling its last USB packet
        // must then be terminated by a zero-length packet.
        private fun endTransfer(payloadLength: Long) {
            if (payloadLength % output.maxPacketSize == 0L)
                check(connection.bulkTransfer(output, ByteArray(0), 0, TIMEOUT_MS) == 0) {
                    "Watch did not accept the end of the data"
                }
        }

        fun drain() {
            val buffer = ByteArray(256 * 1024)
            var drained = 0L
            while (true) {
                val count = transfer(input, buffer, 0, buffer.size, DRAIN_TIMEOUT_MS)
                if (count <= 0) break
                drained += count
            }
            if (drained > 0) Log.i("AndroidMTP", "Discarded $drained stale MTP bytes")
        }

        private fun response(transaction: Int, timeoutMs: Int = TIMEOUT_MS): Reply {
            val (type, code, returnedTransaction, bytes) = readContainer(timeoutMs)
            check(type == RESPONSE && returnedTransaction == transaction && bytes.size % 4 == 0) {
                "Unexpected MTP response type=$type transaction=$returnedTransaction"
            }
            val packet = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return Reply(code, (0 until bytes.size / 4).map { packet.int })
        }

        private data class Container(val type: Int, val code: Int, val transaction: Int,
            val data: ByteArray)

        private fun readContainer(timeoutMs: Int = TIMEOUT_MS): Container {
            val chunk = ByteArray(256 * 1024)
            val first = transfer(input, chunk, 0, chunk.size, timeoutMs)
            check(first >= 12) { "MTP read failed ($first)" }
            val header = ByteBuffer.wrap(chunk, 0, first).order(ByteOrder.LITTLE_ENDIAN)
            val length = header.int.toLong() and 0xffffffffL
            val type = header.short.toInt() and 0xffff
            val code = header.short.toInt() and 0xffff
            val transaction = header.int
            check(length in 12..(16L * 1024 * 1024)) { "Invalid MTP packet length $length" }
            check(first <= length) { "MTP packet exceeded its declared length" }
            val packet = ByteArray(length.toInt())
            chunk.copyInto(packet, 0, 0, first)
            var received = first
            while (received < packet.size) {
                val count = transfer(input, packet, received, packet.size - received)
                check(count > 0) { "MTP read failed after $received bytes ($count)" }
                received += count
            }
            return Container(type, code, transaction, packet.copyOfRange(12, packet.size))
        }

        private fun write(data: ByteArray, length: Int = data.size) {
            var sent = 0
            while (sent < length) {
                val count = transfer(output, data, sent, length - sent)
                check(count > 0) {
                    "Watch did not accept a USB command ($count). Disconnect and reconnect it."
                }
                sent += count
            }
        }

        // Synchronous transfers: a timed-out UsbRequest stays queued in the kernel after close()
        // and the next requestWait() then reaps freed memory, crashing the process.
        // Each one is capped at usbfs' classic 16 KB, the size Android's own MtpDevice uses; larger reads failed
        // at once with -1 and left the watch's reply unread. Callers loop until a container is complete.
        private fun transfer(endpoint: UsbEndpoint, data: ByteArray, offset: Int,
            length: Int, timeoutMs: Int = TIMEOUT_MS): Int {
            val started = System.currentTimeMillis()
            val count = connection.bulkTransfer(endpoint, data, offset, minOf(length, MAX_TRANSFER), timeoutMs)
            if (count < 0 && timeoutMs != DRAIN_TIMEOUT_MS) Log.w("AndroidMTP", "MTP bulk " +
                "${if (endpoint == input) "read" else "write"} of ${minOf(length, MAX_TRANSFER)} bytes failed " +
                "after ${System.currentTimeMillis() - started} ms")
            return count
        }

        private fun header(length: Int, type: Int, code: Int, transaction: Int,
            capacity: Int = 12): ByteBuffer =
            ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(length).putShort(type.toShort()).putShort(code.toShort()).putInt(transaction)
    }
}
