package com.example.adbrelay;

import android.content.Intent;

import java.nio.charset.StandardCharsets;

final class RelayConfig {
    static final String EXTRA_SERVER_HOST = "serverHost";
    static final String EXTRA_SERVER_PORT = "serverPort";
    static final String EXTRA_DEVICE_ID = "deviceId";
    static final String EXTRA_TOKEN = "token";
    static final String EXTRA_ADBD_HOST = "adbdHost";
    static final String EXTRA_ADBD_PORT = "adbdPort";

    final String serverHost;
    final int serverPort;
    final String deviceId;
    final String token;
    final String adbdHost;
    final int adbdPort;

    RelayConfig(
            String serverHost,
            int serverPort,
            String deviceId,
            String token,
            String adbdHost,
            int adbdPort
    ) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.deviceId = deviceId;
        this.token = token;
        this.adbdHost = adbdHost;
        this.adbdPort = adbdPort;
    }

    static RelayConfig fromIntent(Intent intent) {
        return new RelayConfig(
                intent.getStringExtra(EXTRA_SERVER_HOST),
                intent.getIntExtra(EXTRA_SERVER_PORT, 7000),
                intent.getStringExtra(EXTRA_DEVICE_ID),
                intent.getStringExtra(EXTRA_TOKEN),
                intent.getStringExtra(EXTRA_ADBD_HOST),
                intent.getIntExtra(EXTRA_ADBD_PORT, 5555)
        );
    }

    byte[] handshakeBytes() {
        String line = "ADBRELAY/1 " + deviceId + " " + token + "\n";
        return line.getBytes(StandardCharsets.UTF_8);
    }
}
