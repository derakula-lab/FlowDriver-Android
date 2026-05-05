package com.github.derakula;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

public class TunnelVpnService extends VpnService {

    private static final String TAG = "TunnelVpn";
    public static final String ACTION_START = "TUNNEL_START";
    public static final String ACTION_STOP  = "TUNNEL_STOP";

    private ParcelFileDescriptor tunFd;
    private volatile int         childPid = -1;
    private volatile boolean     running;

    private String socks5Host = "127.0.0.1";
    private int    socks5Port = 1080;

    static { System.loadLibrary("fdhelper"); }
    private native int  clearCloexec(int fd);
    private native int  forkExec(String[] cmd, int pipeFd);
    private native void killPid(int pid);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_START.equals(intent.getAction())) {
            if (running) return START_NOT_STICKY;
            String host = intent.getStringExtra("socks5Host");
            socks5Host = (host != null && !host.isEmpty()) ? host : "127.0.0.1";
            socks5Port = intent.getIntExtra("socks5Port", 1080);
            new Thread(this::startTunnel, "tun-start").start();
        } else if (ACTION_STOP.equals(intent.getAction())) {
            stopTunnel();
        }
        return START_NOT_STICKY;
    }

    private void startTunnel() {
        ParcelFileDescriptor[] pipe = null;
        try {
            tunFd = new Builder()
                    .setSession("Flow Driver")
                    .addAddress("26.26.26.1", 24)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("1.1.1.1")
                    .setMtu(1500)
                    .addDisallowedApplication(getPackageName())
                    .establish();

            if (tunFd == null) {
                Log.e(TAG, "tunFd is null");
                return;
            }

            int fd = tunFd.getFd();
            Log.i(TAG, "tunFd established, fd=" + fd);

            int r = clearCloexec(fd);
            Log.d(TAG, "clearCloexec result=" + r);

            String soPath = getApplicationInfo().nativeLibraryDir + "/libtun2socks.so";
            java.io.File soFile = new java.io.File(soPath);
            if (!soFile.exists()) {
                Log.e(TAG, "libtun2socks.so not found");
                return;
            }
            soFile.setExecutable(true, false);

            // pipe برای خواندن لاگ child
            pipe = ParcelFileDescriptor.createPipe();
            int writeFd = pipe[1].getFd();
            clearCloexec(writeFd); // pipe write end هم باید ارثی بشه

            String[] command = {
                    soPath,
                    "-device",   "fd://" + fd,
                    "-proxy",    "socks5://" + socks5Host + ":" + socks5Port,
                    "-loglevel", "warn"
            };

            childPid = forkExec(command, writeFd);
            if (childPid < 0) {
                Log.e(TAG, "forkExec failed");
                return;
            }
            running = true;
            Log.i(TAG, "tun2socks forked, pid=" + childPid);

            // بستن write end در parent
            pipe[1].close();
            pipe[1] = null;

            // خواندن لاگ از pipe
            final ParcelFileDescriptor readEnd = pipe[0];
            pipe[0] = null;
            new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(
                                new FileInputStream(readEnd.getFileDescriptor())))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Log.d(TAG, "[t2s] " + line);
                    }
                } catch (IOException ignored) {}
                try { readEnd.close(); } catch (IOException ignored) {}
                Log.w(TAG, "tun2socks output ended");
                running = false;
            }, "tun2socks-log").start();

        } catch (Exception e) {
            Log.e(TAG, "startTunnel failed: " + e.getMessage(), e);
            running = false;
        } finally {
            if (pipe != null) {
                try { if (pipe[0] != null) pipe[0].close(); } catch (IOException ignored) {}
                try { if (pipe[1] != null) pipe[1].close(); } catch (IOException ignored) {}
            }
        }
    }

    private void stopTunnel() {
        running = false;
        if (childPid > 0) {
            killPid(childPid);
            childPid = -1;
        }
        if (tunFd != null) {
            try { tunFd.close(); } catch (IOException ignored) {}
            tunFd = null;
        }
        stopSelf();
        Log.i(TAG, "Tunnel stopped");
    }

    @Override
    public void onRevoke() {
        stopTunnel();
    }
}