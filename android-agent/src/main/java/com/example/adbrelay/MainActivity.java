package com.example.adbrelay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String PREFS = "relay";

    private EditText serverHost;
    private EditText serverPort;
    private EditText deviceId;
    private EditText token;
    private EditText adbdHost;
    private EditText adbdPort;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestNotificationPermission();

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("ADB Relay Agent");
        title.setTextSize(24);
        content.addView(title);

        serverHost = addInput(content, "VPS host", prefs.getString("serverHost", ""), InputType.TYPE_CLASS_TEXT);
        serverPort = addInput(content, "VPS relay port", prefs.getString("serverPort", "7000"), InputType.TYPE_CLASS_NUMBER);
        deviceId = addInput(content, "Device id", prefs.getString("deviceId", defaultDeviceId()), InputType.TYPE_CLASS_TEXT);
        token = addInput(content, "Token", prefs.getString("token", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        adbdHost = addInput(content, "adbd host", prefs.getString("adbdHost", "127.0.0.1"), InputType.TYPE_CLASS_TEXT);
        adbdPort = addInput(content, "adbd port", prefs.getString("adbdPort", "5555"), InputType.TYPE_CLASS_NUMBER);

        Button start = new Button(this);
        start.setText("Start relay");
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startRelay();
            }
        });
        content.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop relay");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopRelay();
            }
        });
        content.addView(stop);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
    }

    private EditText addInput(LinearLayout parent, String label, String value, int inputType) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(14);
        parent.addView(text);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setText(value);
        parent.addView(input);
        return input;
    }

    private void startRelay() {
        String hostValue = text(serverHost);
        String portValue = text(serverPort);
        String deviceValue = text(deviceId);
        String tokenValue = text(token);
        String adbdHostValue = text(adbdHost);
        String adbdPortValue = text(adbdPort);

        if (hostValue.length() == 0 || tokenValue.length() == 0 || deviceValue.length() == 0 || adbdHostValue.length() == 0) {
            Toast.makeText(this, "VPS host, token, device id, and adbd host are required.", Toast.LENGTH_LONG).show();
            return;
        }
        if (hasWhitespace(deviceValue) || hasWhitespace(tokenValue)) {
            Toast.makeText(this, "Device id and token cannot contain whitespace.", Toast.LENGTH_LONG).show();
            return;
        }

        Integer relayPort = parsePort(portValue);
        if (relayPort == null) {
            Toast.makeText(this, "VPS relay port must be between 1 and 65535.", Toast.LENGTH_LONG).show();
            return;
        }
        Integer localAdbdPort = parsePort(adbdPortValue);
        if (localAdbdPort == null) {
            Toast.makeText(this, "adbd port must be between 1 and 65535.", Toast.LENGTH_LONG).show();
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("serverHost", hostValue)
                .putString("serverPort", String.valueOf(relayPort))
                .putString("deviceId", deviceValue)
                .putString("token", tokenValue)
                .putString("adbdHost", adbdHostValue)
                .putString("adbdPort", String.valueOf(localAdbdPort))
                .apply();

        Intent intent = new Intent(this, RelayForegroundService.class);
        intent.setAction(RelayForegroundService.ACTION_START);
        intent.putExtra(RelayConfig.EXTRA_SERVER_HOST, hostValue);
        intent.putExtra(RelayConfig.EXTRA_SERVER_PORT, relayPort);
        intent.putExtra(RelayConfig.EXTRA_DEVICE_ID, deviceValue);
        intent.putExtra(RelayConfig.EXTRA_TOKEN, tokenValue);
        intent.putExtra(RelayConfig.EXTRA_ADBD_HOST, adbdHostValue);
        intent.putExtra(RelayConfig.EXTRA_ADBD_PORT, localAdbdPort);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "Relay service started.", Toast.LENGTH_SHORT).show();
    }

    private void stopRelay() {
        Intent intent = new Intent(this, RelayForegroundService.class);
        intent.setAction(RelayForegroundService.ACTION_STOP);
        startService(intent);
        Toast.makeText(this, "Relay service stopped.", Toast.LENGTH_SHORT).show();
    }

    private String text(EditText input) {
        return input.getText().toString().trim();
    }

    private Integer parsePort(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0 && parsed <= 65535) return parsed;
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private boolean hasWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) return true;
        }
        return false;
    }

    private String defaultDeviceId() {
        String model = Build.MODEL == null ? "android" : Build.MODEL;
        return model.replaceAll("\\s+", "-").toLowerCase();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }
}
