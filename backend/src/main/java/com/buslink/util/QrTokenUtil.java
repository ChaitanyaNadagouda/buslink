package com.buslink.util;

import java.util.UUID;

public final class QrTokenUtil {

    private QrTokenUtil() {
    }

    public static String generateQrToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
