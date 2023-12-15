package com.espressif.bleota.android

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.espressif.bleota.android.message.BleOTAMessage
import com.espressif.bleota.android.message.EndCommandAckMessage
import com.espressif.bleota.android.message.StartCommandAckMessage
import java.io.*
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayList
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

        private const val MTU_SIZE = 517
        private const val MTU_STATUS_FAILED = 20000
        private const val EXPECT_PACKET_SIZE = 463

        private val SERVICE_UUID = bleUUID("8018")
        private val CHAR_RECV_FW_UUID = bleUUID("8020")
        private val CHAR_PROGRESS_UUID = bleUUID("8021")
        private val CHAR_COMMAND_UUID = bleUUID("8022")
        private val CHAR_CUSTOMER_UUID = bleUUID("8023")

        private const val REQUIRE_CHECKSUM = false
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
    private val sectorAckMark = ByteArray(0)

    fun start(callback: GattCallback) {
        Log.i(TAG, "start OTA")
        stop()

        this.callback = callback
        callback.client = this
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
    }

    fun stop() {
        gatt?.close()
        gatt = null
        callback = null

        packets.clear()
    }

    override fun close() {
        stop()
    }

    private fun initPackets() {
        sectorAckIndex.set(0)
        packets.clear()

        val sectors = ArrayList<ByteArray>()
        ByteArrayInputStream(bin).use {
            val buf = ByteArray(4096)
            while (true) {
                val read = it.read(buf)
                if (read == -1) {
                    break
                }
                val sector = buf.copyOf(read)
                sectors.add(sector)
            }
        }
        if (DEBUG) {
            Log.d(TAG, "initPackets: sectors size = ${sectors.size}")
        }

        val block = ByteArray(packetSize - 3)
        for (element in sectors.withIndex()) {
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
            Log.d(TAG, "initPackets: packets size = ${packets.size}")
        }
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
        when (status) {
            COMMAND_ACK_ACCEPT -> {
            }
        }

        val message = EndCommandAckMessage(status)
        callback?.onOTA(message)
    }

    private fun postBinData() {
        thread {
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
        } else {
            recvFwChar?.value = packet
            gatt?.writeCharacteristic(recvFwChar)
        }
    }

    private fun parseSectorAck(data: ByteArray) {
        try {
            val expectIndex = sectorAckIndex.getAndIncrement()
            val ackIndex = (data[0].toInt() and 0xff) or
                    (data[1].toInt() shl 8 and 0xff00)
            if (ackIndex != expectIndex) {
                Log.w(TAG, "takeSectorAck: Receive error index $ackIndex, expect $expectIndex")
                callback?.onError(1)
                return
            }
            val ackStatus = (data[2].toInt() and 0xff) or
                    (data[3].toInt() shl 8 and 0xff00)
            Log.d(TAG, "takeSectorAck: index=$ackIndex, status=$ackStatus")
            when (ackStatus) {
                BIN_ACK_SUCCESS -> {
                    postNextPacket()
                }
                BIN_ACK_CRC_ERROR -> {
                    callback?.onError(2)
                    return
                }
                BIN_ACK_SECTOR_INDEX_ERROR -> {
                    val devExpectIndex = (data[4].toInt() and 0xff) or
                            (data[5].toInt() shl 8 and 0xff00)
                    if (DEBUG) {
                        Log.w(TAG, "parseSectorAck: device expect index = $devExpectIndex")
                    }
                    callback?.onError(3)
                    return
                }
                BIN_ACK_PAYLOAD_LENGTH_ERROR -> {
                    callback?.onError(4)
                    return
                }
                else -> {
                    callback?.onError(5)
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseSectorAck error")
            if (DEBUG) {
                Log.w(TAG, "parseSectorAck: ", e)
            }
            callback?.onError(-1)
        }
    }

    private fun parseCommandPacket() {
        val packet = commandChar!!.value
        if (DEBUG) {
            Log.i(TAG, "parseCommandPacket: ${packet.contentToString()}")
        }
        if (REQUIRE_CHECKSUM) {
            val crc = (packet[18].toInt() and 0xff) or (packet[19].toInt() shl 8 and 0xff00)
            val checksum = EspCRC16.crc(packet, 0, 18)
            if (crc != checksum) {
                Log.w(TAG, "parseCommandPacket: Checksum error: $crc, expect $checksum")
                return
            }
        }

        val id = (packet[0].toInt() and 0xff) or (packet[1].toInt() shl 8 and 0xff00)
        if (id == COMMAND_ID_ACK) {
            val ackId = (packet[2].toInt() and 0xff) or
                    (packet[3].toInt() shl 8 and 0xff00)
            val ackStatus = (packet[4].toInt() and 0xff) or
                    (packet[5].toInt() shl 8 and 0xff00)
            when (ackId) {
                COMMAND_ID_START -> {
                    receiveCommandStartAck(ackStatus)
                }
                COMMAND_ID_END -> {
                    receiveCommandEndAck(ackStatus)
                }
            }
        }
    }

    open class GattCallback : BluetoothGattCallback() {
        var client: BleOTAClient? = null

        protected fun Int.isGattSuccess(): Boolean {
            return this == BluetoothGatt.GATT_SUCCESS
        }

        protected fun Int.isGattFailed(): Boolean {
            return this != BluetoothGatt.GATT_SUCCESS
        }

        open fun onError(code: Int) {
        }

        open fun onOTA(message: BleOTAMessage) {
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    if (!gatt.requestMtu(MTU_SIZE)) {
                        onMtuChanged(gatt, MTU_SIZE, MTU_STATUS_FAILED)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            client!!.packetSize = if (status.isGattSuccess()) EXPECT_PACKET_SIZE else 20
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status.isGattFailed()) {
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            val recvFwChar = service?.getCharacteristic(CHAR_RECV_FW_UUID)?.also {
                gatt.setCharacteristicNotification(it, true)
            }
            val progressChar = service?.getCharacteristic(CHAR_PROGRESS_UUID)?.also {
                gatt.setCharacteristicNotification(it, true)
            }
            val commandChar = service.getCharacteristic(CHAR_COMMAND_UUID)?.also {
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
            }?.apply {
                if (service != null && recvFwChar != null && progressChar != null && commandChar != null && customerChar != null) {
                    notifyNextDescWrite()
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status.isGattFailed()) {
                return
            }

            val next = client?.notifyNextDescWrite()
            if (next == null) {
                // Set notification enabled completed
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
                client?.postNextPacket()
            }
            if (status.isGattFailed()) {
                Log.w(TAG, "onCharacteristicWrite: status=$status")
                return
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