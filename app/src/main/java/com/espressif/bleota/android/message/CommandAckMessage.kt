package com.espressif.bleota.android.message

import com.espressif.bleota.android.BleOTAClient

abstract class CommandAckMessage(
        val status: Int,
) : BleOTAMessage() {
    companion object {
        const val STATUS_ACCEPT = BleOTAClient.COMMAND_ACK_ACCEPT
        const val STATUS_REFUSE = BleOTAClient.COMMAND_ACK_REFUSE
    }
}