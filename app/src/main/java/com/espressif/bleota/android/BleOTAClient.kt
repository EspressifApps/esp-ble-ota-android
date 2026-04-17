package com.espressif.bleota.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.espressif.bleota.android.message.BleOTAMessage
import com.espressif.bleota.android.message.EndCommandAckMessage
import com.espressif.bleota.android.message.StartCommandAckMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@SuppressLint("MissingPermission")
class BleOTAClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val bin: ByteArray
) : Closeable {
    companion object {
        private const val TAG = "BleOTAClient"
        private const val DEBUG = false

        private const val COMMAND_ID_START = 0x0001
        private const val COMMAND_ID_END = 0x0002
        private const val COMMAND_ID_ACK = 0x0003

        const val COMMAND_ACK_ACCEPT = 0x0000
        const val COMMAND_ACK_REFUSE = 0x0001

        private const val BIN_ACK_SUCCESS = 0x0000
        private const val BIN_ACK_CRC_ERROR = 0x0001
        private const val BIN_ACK_SECTOR_INDEX_ERROR = 0x0002
        private const val BIN_ACK_PAYLOAD_LENGTH_ERROR = 0x0003

        private const val MTU_SIZE = 500
        private const val MTU_STATUS_FAILED = 20000
        private const val EXPECT_PACKET_SIZE = 463

        private const val SECTOR_ACK_TIMEOUT_MS = 30_000L

        /** Max consecutive INDEX_ERR ACKs handled by resync before aborting OTA and disconnecting. */
        private const val MAX_CONSECUTIVE_INDEX_ERR_RESYNC = 10

        private val SERVICE_UUID = bleUUID("8018")
        private val CHAR_RECV_FW_UUID = bleUUID("8020")
        private val CHAR_PROGRESS_UUID = bleUUID("8021")
        private val CHAR_COMMAND_UUID = bleUUID("8022")
        private val CHAR_CUSTOMER_UUID = bleUUID("8023")
    }

    var packetSize = 20

    var gatt: BluetoothGatt? = null
    var service: BluetoothGattService? = null
    var recvFwChar: BluetoothGattCharacteristic? = null
    var progressChar: BluetoothGattCharacteristic? = null
    var commandChar: BluetoothGattCharacteristic? = null
    var customerChar: BluetoothGattCharacteristic? = null

    private var nextNotifyChar: BluetoothGattCharacteristic? = null

    private var callback: GattCallback? = null
    private val packets = LinkedList<ByteArray>()
    private val sectorAckIndex = AtomicInteger(0)
    /** Counts consecutive sector INDEX_ERR ACKs without an intervening success ACK. */
    private val consecutiveIndexErrResyncCount = AtomicInteger(0)
    private val sectorAckMark = ByteArray(0)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var binTransferActive = false

    private val sectorAckTimeoutRunnable = Runnable {
        Log.w(TAG, "sector ACK timeout")
        if (binTransferActive) {
            failOtaTransfer()
            callback?.onError(BleOTAErrors.SECTOR_ACK_TIMEOUT)
        }
    }

    fun connect(callback: GattCallback) {
        Log.i(TAG, "start OTA")
        stop()

        this.callback = callback
        callback.client = this
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun stop() {
        cancelOtaTimeouts()
        binTransferActive = false
        nextNotifyChar = null
        gatt?.close()
        gatt = null
        callback = null

        packets.clear()
        consecutiveIndexErrResyncCount.set(0)
    }

    override fun close() {
        stop()
    }

    fun ota() {
        notifyNextDescWrite()
    }

    private fun cancelOtaTimeouts() {
        mainHandler.removeCallbacks(sectorAckTimeoutRunnable)
    }

    private fun scheduleSectorAckTimeout() {
        mainHandler.removeCallbacks(sectorAckTimeoutRunnable)
        mainHandler.postDelayed(sectorAckTimeoutRunnable, SECTOR_ACK_TIMEOUT_MS)
    }

    private fun failOtaTransfer() {
        binTransferActive = false
        packets.clear()
        consecutiveIndexErrResyncCount.set(0)
    }

    internal fun onRecvFwWriteFailed(gattStatus: Int) {
        cancelOtaTimeouts()
        failOtaTransfer()
        Log.w(TAG, "recv FW write failed: gattStatus=$gattStatus")
        callback?.onError(BleOTAErrors.CHARACTERISTIC_WRITE_FAILED)
    }

    internal fun onDisconnectedDuringTransfer() {
        cancelOtaTimeouts()
        val wasActive = binTransferActive
        failOtaTransfer()
        sectorAckIndex.set(0)
        if (wasActive) {
            callback?.onError(BleOTAErrors.DISCONNECTED_DURING_OTA)
        }
    }

    /**
     * Rebuilds the FW write queue starting at [firstSector] (0-based), and sets
     * [sectorAckIndex] to the same value so the next sector ACK matches device expectation.
     */
    private fun rebuildPacketsFromSector(firstSector: Int) {
        val sectors = ArrayList<ByteArray>()
        ByteArrayInputStream(bin).use {
            val buf = ByteArray(4096)
            while (true) {
                val read = it.read(buf)
                if (read == -1) {
                    break
                }
                sectors.add(buf.copyOf(read))
            }
        }
        if (sectors.isEmpty()) {
            sectorAckIndex.set(0)
            packets.clear()
            return
        }

        val from = firstSector.coerceIn(0, sectors.lastIndex)
        sectorAckIndex.set(from)
        packets.clear()

        val block = ByteArray(packetSize - 3)
        for (element in sectors.withIndex()) {
            if (element.index < from) {
                continue
            }
            val sector = element.value
            val index = element.index
            val stream = ByteArrayInputStream(sector)
            var sequence = 0
            while (true) {
                val read = stream.read(block)
                if (read == -1) {
                    break
                }
                var crc = 0
                val bLast = stream.available() == 0
                if (bLast) {
                    sequence = -1
                    crc = EspCRC16.crc(sector)
                }

                val len = if (bLast) read + 5 else read + 3
                val packet = ByteArrayOutputStream(len).use {
                    it.write(index and 0xff)
                    it.write(index shr 8 and 0xff)
                    it.write(sequence)
                    it.write(block, 0, read)
                    if (bLast) {
                        it.write(crc and 0xff)
                        it.write(crc shr 8 and 0xff)
                    }
                    it.toByteArray()
                }

                ++sequence

                packets.add(packet)
            }
            packets.add(sectorAckMark)
        }
        if (DEBUG) {
            Log.d(TAG, "rebuildPacketsFromSector: from=$from sectors=${sectors.size} queue=${packets.size}")
        }
    }

    private fun initPackets() {
        consecutiveIndexErrResyncCount.set(0)
        rebuildPacketsFromSector(0)
    }

    private fun notifyNextDescWrite(): BluetoothGattCharacteristic? {
        return when (nextNotifyChar) {
            null -> {
                nextNotifyChar = recvFwChar
                recvFwChar
            }
            recvFwChar -> {
                nextNotifyChar = progressChar
                progressChar
            }
            progressChar -> {
                nextNotifyChar = commandChar
                commandChar
            }
            commandChar -> {
                nextNotifyChar = customerChar
                customerChar
            }
            customerChar -> {
                null
            }
            else -> {
                null
            }
        }?.apply {
            getDescriptor(UUID_NOTIFY_DESCRIPTOR)?.apply {
                value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                gatt?.writeDescriptor(this)
            }
        }
    }

    private fun genCommandPacket(id: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(20)
        packet[0] = (id and 0xff).toByte()
        packet[1] = (id shr 8 and 0xff).toByte()
        System.arraycopy(payload, 0, packet, 2, payload.size)
        val crc = EspCRC16.crc(packet, 0, 18)
        packet[18] = (crc and 0xff).toByte()
        packet[19] = (crc shr 8 and 0xff).toByte()
        return packet
    }

    private fun postCommandStart() {
        Log.i(TAG, "postCommandStart")
        val binSize = bin.size
        val payload = byteArrayOf(
            (binSize and 0xff).toByte(),
            (binSize shr 8 and 0xff).toByte(),
            (binSize shr 16 and 0xff).toByte(),
            (binSize shr 24 and 0xff).toByte(),
        )
        val packet = genCommandPacket(COMMAND_ID_START, payload)
        commandChar?.value = packet
        gatt?.writeCharacteristic(commandChar)
    }

    private fun receiveCommandStartAck(status: Int) {
        Log.i(TAG, "receiveCommandStartAck: status=$status")
        when (status) {
            COMMAND_ACK_ACCEPT -> {
                postBinData()
            }
            COMMAND_ACK_REFUSE -> {
                callback?.onError(BleOTAErrors.START_REFUSED)
            }
        }

        val message = StartCommandAckMessage(status)
        callback?.onOTA(message)
    }

    private fun postCommandEnd() {
        Log.i(TAG, "postCommandEnd")
        val payload = ByteArray(0)
        val packet = genCommandPacket(COMMAND_ID_END, payload)
        commandChar?.value = packet
        gatt?.writeCharacteristic(commandChar)
    }

    private fun receiveCommandEndAck(status: Int) {
        Log.i(TAG, "receiveCommandEndAck: status=$status")
        binTransferActive = false
        when (status) {
            COMMAND_ACK_ACCEPT -> {
            }
            COMMAND_ACK_REFUSE -> {
                callback?.onError(BleOTAErrors.END_REFUSED)
            }
        }

        val message = EndCommandAckMessage(status)
        callback?.onOTA(message)
    }

    private fun postBinData() {
        thread {
            binTransferActive = true
            initPackets()

            postNextPacket()
        }
    }

    private fun postNextPacket() {
        val packet = packets.pollFirst()
        if (packet == null) {
            postCommandEnd()
        } else if (packet === sectorAckMark) {
            if (DEBUG) {
                Log.d(TAG, "postNextPacket: wait for sector ACK")
            }
            scheduleSectorAckTimeout()
        } else {
            recvFwChar?.value = packet
            gatt?.writeCharacteristic(recvFwChar)
        }
    }

    private fun parseSectorAck(data: ByteArray?) {
        cancelOtaTimeouts()
        try {
            if (data == null || data.size < 20) {
                Log.w(TAG, "parseSectorAck: short payload, size=${data?.size}")
                callback?.onError(BleOTAErrors.SECTOR_ACK_TOO_SHORT)
                failOtaTransfer()
                return
            }
            val crcRecv = u16le(data, 18)
            val crcCalc = EspCRC16.crc(data, 0, 18)
            if (crcRecv != crcCalc) {
                Log.w(TAG, "parseSectorAck: CRC mismatch recv=0x${crcRecv.toString(16)} calc=0x${crcCalc.toString(16)}")
                callback?.onError(BleOTAErrors.SECTOR_ACK_CRC_INVALID)
                failOtaTransfer()
                return
            }

            val expectIndex = sectorAckIndex.get()
            val ackIndex = u16le(data, 0)
            if (ackIndex != expectIndex) {
                Log.w(TAG, "parseSectorAck: index $ackIndex, expect $expectIndex")
                callback?.onError(BleOTAErrors.SECTOR_ACK_INDEX_MISMATCH)
                failOtaTransfer()
                return
            }

            val ackStatus = u16le(data, 2)
            val nextExpected = u16le(data, 4)
            Log.d(TAG, "parseSectorAck: index=$ackIndex, status=$ackStatus, nextExpected=$nextExpected")

            when (ackStatus) {
                BIN_ACK_SUCCESS -> {
                    consecutiveIndexErrResyncCount.set(0)
                    val expectedNext = (ackIndex + 1) and 0xffff
                    if (nextExpected != expectedNext) {
                        Log.w(TAG, "parseSectorAck: nextExpected mismatch got=$nextExpected want=$expectedNext")
                        callback?.onError(BleOTAErrors.SECTOR_ACK_NEXT_MISMATCH)
                        failOtaTransfer()
                        return
                    }
                    sectorAckIndex.incrementAndGet()
                    postNextPacket()
                }
                BIN_ACK_CRC_ERROR -> {
                    callback?.onError(BleOTAErrors.BIN_CRC_REJECT)
                    failOtaTransfer()
                }
                BIN_ACK_SECTOR_INDEX_ERROR -> {
                    val devExpectIndex = u16le(data, 4)
                    val attempt = consecutiveIndexErrResyncCount.incrementAndGet()
                    if (attempt >= MAX_CONSECUTIVE_INDEX_ERR_RESYNC) {
                        Log.e(
                            TAG,
                            "parseSectorAck: INDEX_ERR resync exhausted ($attempt consecutive), abort OTA"
                        )
                        failOtaTransfer()
                        callback?.onError(BleOTAErrors.SECTOR_INDEX_RESYNC_EXHAUSTED)
                        return
                    }
                    Log.w(
                        TAG,
                        "parseSectorAck: INDEX_ERR device expect=$devExpectIndex, resync ($attempt/$MAX_CONSECUTIVE_INDEX_ERR_RESYNC)"
                    )
                    rebuildPacketsFromSector(devExpectIndex)
                    callback?.onSectorIndexResync(sectorAckIndex.get())
                    postNextPacket()
                }
                BIN_ACK_PAYLOAD_LENGTH_ERROR -> {
                    callback?.onError(BleOTAErrors.DEVICE_PAYLOAD_LENGTH_ERROR)
                    failOtaTransfer()
                }
                else -> {
                    callback?.onError(BleOTAErrors.UNKNOWN_BIN_ACK_STATUS)
                    failOtaTransfer()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseSectorAck error", e)
            failOtaTransfer()
            callback?.onError(BleOTAErrors.SECTOR_ACK_PARSE)
        }
    }

    private fun parseCommandPacket() {
        val packet = commandChar?.value
        if (packet == null) {
            Log.w(TAG, "parseCommandPacket: null value")
            return
        }
        if (DEBUG) {
            Log.i(TAG, "parseCommandPacket: ${packet.contentToString()}")
        }
        if (packet.size < 20) {
            Log.w(TAG, "parseCommandPacket: size=${packet.size}")
            callback?.onError(BleOTAErrors.COMMAND_PACKET_SIZE)
            return
        }
        val crc = u16le(packet, 18)
        val checksum = EspCRC16.crc(packet, 0, 18)
        if (crc != checksum) {
            Log.w(TAG, "parseCommandPacket: Checksum error: $crc, expect $checksum")
            callback?.onError(BleOTAErrors.COMMAND_CHECKSUM)
            return
        }

        val id = u16le(packet, 0)
        if (id != COMMAND_ID_ACK) {
            Log.w(TAG, "parseCommandPacket: unexpected id=0x${id.toString(16)}")
            callback?.onError(BleOTAErrors.COMMAND_NOTIFY_UNEXPECTED_ID)
            return
        }
        val ackId = u16le(packet, 2)
        val ackStatus = u16le(packet, 4)
        when (ackId) {
            COMMAND_ID_START -> {
                receiveCommandStartAck(ackStatus)
            }
            COMMAND_ID_END -> {
                receiveCommandEndAck(ackStatus)
            }
            else -> {
                Log.w(TAG, "parseCommandPacket: unknown ackId=0x${ackId.toString(16)}")
                callback?.onError(BleOTAErrors.UNKNOWN_COMMAND_ACK_ID)
            }
        }
    }

    open class GattCallback : BluetoothGattCallback() {
        var client: BleOTAClient? = null

        protected fun isGattSuccess(status: Int): Boolean {
            return status == BluetoothGatt.GATT_SUCCESS
        }

        protected fun isGattFailed(status: Int): Boolean {
            return status != BluetoothGatt.GATT_SUCCESS
        }

        open fun onError(code: Int) {
        }

        open fun onOTA(message: BleOTAMessage) {
        }

        /** Device asked to resend from this sector; default no-op (Activity may show status). */
        open fun onSectorIndexResync(fromSector: Int) {
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_DISCONNECTED -> {
                    client?.onDisconnectedDuringTransfer()
                }
                BluetoothGatt.STATE_CONNECTED -> {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    if (!gatt.requestMtu(MTU_SIZE)) {
                        onMtuChanged(gatt, MTU_SIZE, MTU_STATUS_FAILED)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            client!!.packetSize = if (isGattSuccess(status)) EXPECT_PACKET_SIZE else 20
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (isGattFailed(status)) {
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            val recvFwChar = service?.getCharacteristic(CHAR_RECV_FW_UUID)?.also {
                gatt.setCharacteristicNotification(it, true)
            }
            val progressChar = service?.getCharacteristic(CHAR_PROGRESS_UUID)?.also {
                gatt.setCharacteristicNotification(it, true)
            }
            val commandChar = service?.getCharacteristic(CHAR_COMMAND_UUID)?.also {
                gatt.setCharacteristicNotification(it, true)
            }
            val customerChar = service?.getCharacteristic(CHAR_CUSTOMER_UUID)?.also {
                gatt.setCharacteristicNotification(it, true)
            }

            client?.also {
                it.service = service
                it.recvFwChar = recvFwChar
                it.progressChar = progressChar
                it.commandChar = commandChar
                it.customerChar = customerChar
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (isGattFailed(status)) {
                return
            }

            val next = client?.notifyNextDescWrite()
            if (next == null) {
                Log.d(TAG, "onDescriptorWrite: Set notification enabled completed")
                client?.postCommandStart()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (DEBUG) {
                Log.d(TAG, "onCharacteristicWrite: status=$status, char=${characteristic.uuid}")
            }
            if (characteristic == client?.recvFwChar) {
                if (isGattSuccess(status)) {
                    client?.postNextPacket()
                } else {
                    client?.onRecvFwWriteFailed(status)
                }
            }
            if (isGattFailed(status) && characteristic != client?.recvFwChar) {
                Log.w(TAG, "onCharacteristicWrite: status=$status, char=${characteristic.uuid}")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (DEBUG) {
                Log.d(TAG, "onCharacteristicChanged: char=${characteristic.uuid}")
            }
            when (characteristic) {
                client?.recvFwChar -> {
                    client?.parseSectorAck(characteristic.value)
                }
                client?.progressChar -> {
                }
                client?.commandChar -> {
                    client?.parseCommandPacket()
                }
                client?.customerChar -> {
                }
            }
        }
    }
}

private fun u16le(data: ByteArray, offset: Int): Int {
    return (data[offset].toInt() and 0xff) or (data[offset + 1].toInt() shl 8 and 0xff00)
}
