package com.espressif.bleota.android

/** Numeric codes passed to [BleOTAClient.GattCallback.onError]; see [messageFor]. */
object BleOTAErrors {
    const val SECTOR_ACK_INDEX_MISMATCH = 1
    const val BIN_CRC_REJECT = 2
    const val DEVICE_SECTOR_INDEX_ERROR = 3
    const val DEVICE_PAYLOAD_LENGTH_ERROR = 4
    const val UNKNOWN_BIN_ACK_STATUS = 5
    const val SECTOR_ACK_CRC_INVALID = 6
    const val SECTOR_ACK_TOO_SHORT = 7
    const val SECTOR_ACK_NEXT_MISMATCH = 8
    const val SECTOR_ACK_PARSE = -1

    const val DISCONNECTED_DURING_OTA = 10
    const val SECTOR_ACK_TIMEOUT = 11
    const val CHARACTERISTIC_WRITE_FAILED = 12
    const val COMMAND_PACKET_SIZE = 13
    const val COMMAND_CHECKSUM = 14
    const val UNKNOWN_COMMAND_ACK_ID = 15
    const val START_REFUSED = 16
    const val END_REFUSED = 17
    const val COMMAND_NOTIFY_UNEXPECTED_ID = 18
    /** Consecutive sector INDEX_ERR + resync attempts exceeded limit (see BleOTAClient). */
    const val SECTOR_INDEX_RESYNC_EXHAUSTED = 21

    fun messageFor(code: Int): String = when (code) {
        SECTOR_ACK_INDEX_MISMATCH -> "Sector ACK: index mismatch (host vs device)"
        BIN_CRC_REJECT -> "Sector ACK: device CRC error on received sector"
        DEVICE_SECTOR_INDEX_ERROR -> "Sector ACK: device sector index error"
        DEVICE_PAYLOAD_LENGTH_ERROR -> "Sector ACK: device payload/packet length error"
        UNKNOWN_BIN_ACK_STATUS -> "Sector ACK: unknown status code"
        SECTOR_ACK_CRC_INVALID -> "Sector ACK: notification CRC invalid"
        SECTOR_ACK_TOO_SHORT -> "Sector ACK: payload too short (< 20 bytes)"
        SECTOR_ACK_NEXT_MISMATCH -> "Sector ACK: success but next-sector field inconsistent"
        SECTOR_ACK_PARSE -> "Sector ACK: parse error"
        DISCONNECTED_DURING_OTA -> "Disconnected during OTA transfer"
        SECTOR_ACK_TIMEOUT -> "Timed out waiting for sector ACK"
        CHARACTERISTIC_WRITE_FAILED -> "GATT write failed (firmware characteristic)"
        COMMAND_PACKET_SIZE -> "Command notify: invalid packet size"
        COMMAND_CHECKSUM -> "Command notify: CRC mismatch"
        UNKNOWN_COMMAND_ACK_ID -> "Command notify: unknown ACK command id"
        START_REFUSED -> "Device refused OTA start"
        END_REFUSED -> "Device refused OTA end"
        COMMAND_NOTIFY_UNEXPECTED_ID -> "Command notify: unexpected message id"
        SECTOR_INDEX_RESYNC_EXHAUSTED -> "Sector INDEX_ERR: too many consecutive resyncs, giving up"
        else -> "Error ($code)"
    }
}
