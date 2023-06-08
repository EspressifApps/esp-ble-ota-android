package com.espressif.bleota.android;

public class EspCRC16 {
    public static int crc(byte[] data) {
        return crc(data, 0, data.length);
    }

    public static int crc(byte[] data, int offset, int length) {
        int crc16 = 0;
        for (int cur = offset; cur < length; ++cur) {
            int aInt = data[cur] & 0xff;
            crc16 ^= aInt << 8;
            for (int i = 0; i < 8; ++i) {
                if ((crc16 & 0x8000) != 0) {
                    crc16 = ((crc16 << 1) ^ 0x1021) & 0xffff;
                } else {
                    crc16 = (crc16 << 1) & 0xffff;
                }
            }
        }

        return crc16;
    }
}
