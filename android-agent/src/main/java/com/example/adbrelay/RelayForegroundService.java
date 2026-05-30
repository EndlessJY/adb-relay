package com.example.adbrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class RelayForegroundService extends Service {
    static final String ACTION_START = "com.example.adbrelay.START";
    static final String ACTION_STOP = "com.example.adbrelay.STOP";

    private static final String CHANNEL_ID = "adb-relay";
    private static final int NOTIFICATION_ID = 7;
    private static final int COPY_BUFFER_SIZE = 256 * 1024;
    private static final int SOCKET_BUFFER_SIZE = 1024 * 1024;
    private static final long WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L;

    private Thread worker;
    private WorkerControl workerControl;
    private PowerManager.WakeLock wakeLock;

    private static final class WorkerControl {
        final AtomicBoolean running = new AtomicBoolean(true);
        volatile Socket relay;
        volatile Socket adbd;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopWorker();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }

        RelayConfig config = RelayConfig.fromIntent(intent);
        startForeground(NOTIFICATION_ID, notification("Starting"));
        startWorker(config);
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        stopWorker();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private synchronized void startWorker(final RelayConfig config) {
        stopWorker();
        final WorkerControl control = new WorkerControl();
        workerControl = control;
        acquireWakeLock();
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop(config, control);
            }
        }, "adb-relay-worker");
        worker.start();
    }

    private synchronized void stopWorker() {
        WorkerControl control = workerControl;
        Thread thread = worker;

        if (control != null) {
            control.running.set(false);
            closeQuietly(control.relay);
            closeQuietly(control.adbd);
        }
        if (thread != null) {
            thread.interrupt();
            joinQuietly(thread, 2000);
        }
        worker = null;
        workerControl = null;
        releaseWakeLock();
    }

    private void runLoop(RelayConfig config, WorkerControl control) {
        while (control.running.get()) {
            try {
                updateNotification("Connecting to relay");
                connectOnce(config, control);
            } catch (IOException error) {
                if (control.running.get()) {
                    updateNotification("Retrying: " + shortMessage(error));
                    sleep(2000, control);
                }
            }
        }
    }

    private void connectOnce(RelayConfig config, WorkerControl control) throws IOException {
        Socket relay = new Socket();
        Socket adbd = new Socket();
        control.relay = relay;
        control.adbd = adbd;
        try {
            tuneSocket(relay);
            relay.connect(new InetSocketAddress(config.serverHost, config.serverPort), 10000);
            relay.getOutputStream().write(config.handshakeBytes());
            relay.getOutputStream().flush();

            updateNotification("Connecting to adbd");
            tuneSocket(adbd);
            adbd.connect(new InetSocketAddress(config.adbdHost, config.adbdPort), 5000);

            updateNotification("Relaying " + config.deviceId);
            pipeBidirectional(relay, adbd, control);
        } finally {
            closeQuietly(relay);
            closeQuietly(adbd);
            if (control.relay == relay) control.relay = null;
            if (control.adbd == adbd) control.adbd = null;
        }
    }

    private void pipeBidirectional(final Socket relay, final Socket adbd, final WorkerControl control) throws IOException {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<IOException> failure = new AtomicReference<IOException>();

        Thread relayToAdbd = new Thread(new Runnable() {
            @Override
            public void run() {
                copy(relay, adbd, done, failure, control);
            }
        }, "relay-to-adbd");
        Thread adbdToRelay = new Thread(new Runnable() {
            @Override
            public void run() {
                copy(adbd, relay, done, failure, control);
            }
        }, "adbd-to-relay");

        relayToAdbd.start();
        adbdToRelay.start();
        try {
            done.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Relay interrupted.", error);
        } finally {
            closeQuietly(relay);
            closeQuietly(adbd);
        }

        joinQuietly(relayToAdbd, 1000);
        joinQuietly(adbdToRelay, 1000);
        if (failure.get() != null && control.running.get()) {
            throw failure.get();
        }
    }

    private void copy(
            Socket inputSocket,
            Socket outputSocket,
            CountDownLatch done,
            AtomicReference<IOException> failure,
            WorkerControl control
    ) {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try {
            InputStream input = inputSocket.getInputStream();
            OutputStream output = outputSocket.getOutputStream();
            while (control.running.get()) {
                int read = input.read(buffer);
                if (read == -1) break;
                output.write(buffer, 0, read);
            }
            output.flush();
        } catch (IOException error) {
            failure.compareAndSet(null, error);
        } finally {
            done.countDown();
        }
    }

    private void updateNotification(String status) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification(status));
        }
    }

    private Notification notification(String status) {
        Intent activityIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, activityIntent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("ADB Relay Agent")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "ADB Relay",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager manager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (manager != null) {
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ADBRelay:relay");
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private void sleep(long millis, WorkerControl control) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            control.running.set(false);
        }
    }

    private void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void joinQuietly(Thread thread, long timeoutMillis) {
        try {
            thread.join(timeoutMillis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void tuneSocket(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
        socket.setSendBufferSize(SOCKET_BUFFER_SIZE);
    }

    private String shortMessage(IOException error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
