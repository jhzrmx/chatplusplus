package com.jhz.llamachatplusplus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the llama.cpp "llama-server" binary (shipped as libllama_server.so) as a child
 * process inside a foreground service. State is exposed through static getters so the
 * activity can re-attach at any time.
 */
public class ServerService extends Service {

    public interface Listener {
        void onStateChanged();
    }

    public static final int STOPPED = 0, LOADING = 1, RUNNING = 2, ERROR = 3;

    static final String ACTION_START = "com.jhz.llamachatplusplus.START";
    static final String ACTION_STOP = "com.jhz.llamachatplusplus.STOP";
    static final String EXTRA_MODEL = "model";

    private static final String CHANNEL = "server";
    private static final int NOTIF_ID = 1;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final StringBuilder LOG = new StringBuilder();

    private static volatile int state = STOPPED;
    private static volatile String modelPath;
    private static volatile String errorMessage;
    private static volatile int port = 8080;
    private static volatile boolean exposed;
    private static volatile Listener listener;
    private static int generation; // guarded by LOCK

    private Process process; // guarded by LOCK
    private PowerManager.WakeLock wakeLock;

    // ---------- static API ----------

    public static void start(Context c, String model) {
        Intent i = new Intent(c, ServerService.class).setAction(ACTION_START).putExtra(EXTRA_MODEL, model);
        c.startForegroundService(i);
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, ServerService.class).setAction(ACTION_STOP));
    }

    public static void setListener(Listener l) { listener = l; }
    public static int getState() { return state; }
    public static String getModelPath() { return modelPath; }
    public static String getError() { return errorMessage; }
    public static int getPort() { return port; }
    public static boolean isExposed() { return exposed; }

    public static String getLog() {
        synchronized (LOG) { return LOG.toString(); }
    }

    private static void appendLog(String line) {
        synchronized (LOG) {
            LOG.append(line).append('\n');
            if (LOG.length() > 60000) LOG.delete(0, LOG.length() - 40000);
        }
    }

    private static String lastLogLines(int n) {
        String[] lines = getLog().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - n); i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().trim();
    }

    private static void setState(int st, String err) {
        state = st;
        errorMessage = err;
        MAIN.post(new Runnable() {
            @Override public void run() {
                Listener l = listener;
                if (l != null) l.onStateChanged();
            }
        });
    }

    // ---------- Service ----------

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            startForeground(NOTIF_ID, buildNotification("Loading model…"));
            startServer(intent.getStringExtra(EXTRA_MODEL));
        } else {
            stopServer();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        synchronized (LOCK) {
            generation++;
            killLocked();
        }
        releaseWakeLock();
        if (state == LOADING || state == RUNNING) setState(STOPPED, null);
        super.onDestroy();
    }

    private void stopServer() {
        synchronized (LOCK) {
            generation++;
            killLocked();
        }
        releaseWakeLock();
        setState(STOPPED, null);
        stopForeground(true);
        stopSelf();
    }

    /** Must hold LOCK. Returns the old process (already destroyed) or null. */
    private Process killLocked() {
        Process old = process;
        process = null;
        if (old != null) old.destroy();
        return old;
    }

    private void fail(String msg) {
        appendLog(msg);
        releaseWakeLock();
        setState(ERROR, msg);
        MAIN.post(new Runnable() {
            @Override public void run() {
                stopForeground(true);
                stopSelf();
            }
        });
    }

    private void startServer(String model) {
        final int gen;
        final Process old;
        synchronized (LOCK) {
            generation++;
            gen = generation;
            old = killLocked();
        }
        synchronized (LOG) { LOG.setLength(0); }
        modelPath = model;
        port = Prefs.port(this);
        exposed = Prefs.expose(this);

        File bin = new File(getApplicationInfo().nativeLibraryDir, "libllama_server.so");
        if (!bin.exists()) {
            fail("Native server binary not found for this device's ABI.\n"
                    + "Build it with tools/build-llama-server.sh and rebuild the app.");
            return;
        }
        if (model == null || !new File(model).exists()) {
            fail("Model file not found.");
            return;
        }

        List<String> cmd = new ArrayList<String>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("-m"); cmd.add(model);
        cmd.add("--host"); cmd.add(exposed ? "0.0.0.0" : "127.0.0.1");
        cmd.add("--port"); cmd.add(String.valueOf(port));
        cmd.add("-c"); cmd.add(String.valueOf(Prefs.ctx(this)));
        cmd.add("-t"); cmd.add(String.valueOf(Prefs.threads(this)));
        String key = Prefs.apiKey(this).trim();
        if (key.length() > 0) {
            cmd.add("--api-key"); cmd.add(key);
        }
        appendLog("$ " + join(cmd));

        final ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(getFilesDir());

        acquireWakeLock();
        setState(LOADING, null);

        new Thread(new Runnable() {
            @Override public void run() { runProcess(gen, old, pb); }
        }, "llama-server").start();
    }

    private void runProcess(final int gen, Process old, ProcessBuilder pb) {
        if (old != null) {
            try { old.waitFor(); } catch (InterruptedException ignored) { }
        }

        Process started;
        synchronized (LOCK) {
            if (gen != generation) return;
            try {
                started = pb.start();
            } catch (IOException e) {
                fail("Cannot start server: " + e.getMessage());
                return;
            }
            process = started;
        }
        final Process proc = started;

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                    String line;
                    while ((line = r.readLine()) != null) appendLog(line);
                } catch (IOException ignored) { }
            }
        }, "llama-log").start();

        new Thread(new Runnable() {
            @Override public void run() { pollHealth(gen, proc); }
        }, "llama-health").start();

        int code = -1;
        try {
            code = proc.waitFor();
        } catch (InterruptedException ignored) { }

        boolean current;
        synchronized (LOCK) {
            current = gen == generation;
            if (current) process = null;
        }
        if (current) {
            // exited without being asked to
            if (code == 0) {
                releaseWakeLock();
                setState(STOPPED, null);
                MAIN.post(new Runnable() {
                    @Override public void run() { stopForeground(true); stopSelf(); }
                });
            } else {
                fail("Server exited with code " + code + "\n" + lastLogLines(6));
            }
        }
    }

    private void pollHealth(int gen, Process proc) {
        while (true) {
            synchronized (LOCK) {
                if (gen != generation) return;
            }
            if (!isAlive(proc)) return;
            if (healthOk()) {
                synchronized (LOCK) {
                    if (gen != generation) return;
                }
                setState(RUNNING, null);
                updateNotification();
                return;
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }

    private static boolean isAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private static boolean healthOk() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/health").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            // never print the API key into the log
            boolean secret = i > 0 && "--api-key".equals(l.get(i - 1));
            if (i > 0) sb.append(' ');
            sb.append(secret ? "****" : l.get(i));
        }
        return sb.toString();
    }

    // ---------- wake lock ----------

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chatpp:server");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ---------- notification ----------

    private void updateNotification() {
        String name = modelPath == null ? "" : new File(modelPath).getName();
        String text = name + " • " + (exposed ? "0.0.0.0" : "127.0.0.1") + ":" + port;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIF_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Server", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        Notification.Builder b = new Notification.Builder(this, CHANNEL);
        int imm = PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | imm);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ServerService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | imm);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Chat++ server")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(0, "Stop", stop);
        return b.build();
    }
}
