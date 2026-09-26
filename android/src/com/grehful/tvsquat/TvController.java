package com.grehful.tvsquat;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** TV 전원 제어/상태 확인. 모든 네트워크 작업은 백그라운드 스레드에서 하고, 화면은 status()로 결과를 읽는다. */
final class TvController {
    private static final String TAG = "TvSquat";
    private static final int STATUS_TIMEOUT_MS = 2000;
    private static final int PAIR_WAIT_MS = 30000;
    private static final int KEY_WAIT_MS = 5000;

    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    private volatile String host = "";
    private volatile String mac = "";
    private volatile Boolean on = null; // null = 모름(TV 미설정)
    private volatile String message = "";
    private volatile boolean busy = false;
    private volatile JSONArray discovered = new JSONArray();

    TvController(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE);
        poller.scheduleWithFixedDelay(this::poll, 1, 5, TimeUnit.SECONDS);
    }

    void shutdown() {
        poller.shutdownNow();
        worker.shutdownNow();
    }

    void configure(String host, String mac) {
        host = host == null ? "" : host.trim();
        if (!host.equals(this.host)) on = null;
        this.host = host;
        this.mac = mac == null ? "" : mac.trim();
    }

    private String token() { return prefs.getString("token:" + host, ""); }

    private void saveToken(String token) {
        if (token != null && !token.isEmpty()) prefs.edit().putString("token:" + host, token).apply();
    }

    String status() {
        JSONObject o = new JSONObject();
        try {
            o.put("on", on == null ? JSONObject.NULL : on);
            o.put("message", message);
            o.put("busy", busy);
            o.put("paired", !token().isEmpty());
            o.put("discovered", discovered);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    private void poll() {
        String h = host;
        if (h.isEmpty()) {
            on = null;
            return;
        }
        on = "on".equals(SamsungRemote.powerState(h, STATUS_TIMEOUT_MS));
    }

    private void run(String label, Task task) {
        worker.execute(() -> {
            busy = true;
            try {
                if (host.isEmpty() && !"discover".equals(label)) {
                    message = "TV IP 주소를 먼저 설정하세요";
                    return;
                }
                task.run();
            } catch (Exception e) {
                Log.w(TAG, label + " 실패", e);
                message = label + " 실패: " + e.getMessage();
            } finally {
                busy = false;
                poll();
            }
        });
    }

    private interface Task {
        void run() throws Exception;
    }

    private void sendKey(String key, int waitMs) throws Exception {
        String h = host;
        saveToken(SamsungRemote.sendKey(h, token(), key, waitMs));
    }

    void pair() {
        run("TV 연결", () -> {
            message = "TV 화면에서 '허용'을 눌러주세요… (30초)";
            // 볼륨을 올렸다 내려서 연결을 확인 (화면 변화 없음)
            sendKey("KEY_VOLUP", PAIR_WAIT_MS);
            sendKey("KEY_VOLDOWN", KEY_WAIT_MS);
            message = "TV 연결 완료!";
        });
    }

    void powerOff() {
        run("TV 끄기", () -> {
            if (!"on".equals(SamsungRemote.powerState(host, STATUS_TIMEOUT_MS))) {
                message = "TV가 이미 꺼져 있어요";
                return;
            }
            sendKey("KEY_POWER", KEY_WAIT_MS);
            message = "TV를 껐어요";
        });
    }

    void powerOn() {
        run("TV 켜기", () -> {
            String state = SamsungRemote.powerState(host, STATUS_TIMEOUT_MS);
            if ("on".equals(state)) {
                message = "TV가 이미 켜져 있어요";
                return;
            }
            if (!mac.isEmpty()) {
                for (int i = 0; i < 3; i++) {
                    sendMagicPacket(mac, host);
                    Thread.sleep(300);
                }
            }
            // 대기모드(standby)에서도 응답하는 모델은 KEY_POWER로 켜야 함
            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline) {
                state = SamsungRemote.powerState(host, STATUS_TIMEOUT_MS);
                if ("on".equals(state)) {
                    message = "TV를 켰어요";
                    return;
                }
                if (state != null) {
                    sendKey("KEY_POWER", KEY_WAIT_MS);
                    message = "TV를 켰어요";
                    return;
                }
                Thread.sleep(1000);
            }
            message = mac.isEmpty()
                    ? "TV를 켤 수 없어요: TV MAC 주소를 입력해야 켤 수 있어요"
                    : "TV가 응답하지 않아요. TV 설정의 '모바일로 TV 켜기'를 확인하세요";
        });
    }

    /** SSDP로 같은 와이파이의 삼성 TV를 찾는다. */
    void discover() {
        run("discover", () -> {
            message = "TV를 찾는 중…";
            discovered = new JSONArray();
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock lock = wifi == null ? null : wifi.createMulticastLock("tvsquat");
            Set<String> hosts = new LinkedHashSet<>();
            if (lock != null) lock.acquire();
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setSoTimeout(500);
                InetAddress group = InetAddress.getByName("239.255.255.250");
                for (String st : new String[]{"urn:samsung.com:device:RemoteControlReceiver:1",
                        "urn:dial-multiscreen-org:service:dial:1"}) {
                    byte[] req = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
                            + "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII);
                    sock.send(new DatagramPacket(req, req.length, group, 1900));
                }
                long deadline = System.currentTimeMillis() + 3500;
                byte[] buf = new byte[2048];
                while (System.currentTimeMillis() < deadline) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(p);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    hosts.add(p.getAddress().getHostAddress());
                }
            } finally {
                if (lock != null && lock.isHeld()) lock.release();
            }
            JSONArray found = new JSONArray();
            for (String h : hosts) {
                JSONObject info = SamsungRemote.deviceInfo(h, STATUS_TIMEOUT_MS);
                JSONObject dev = info == null ? null : info.optJSONObject("device");
                if (dev == null) continue; // 삼성 TV가 아님
                JSONObject tv = new JSONObject();
                tv.put("ip", h);
                tv.put("name", dev.optString("name", info.optString("name", "삼성 TV")));
                tv.put("model", dev.optString("modelName", ""));
                tv.put("mac", dev.optString("wifiMac", ""));
                found.put(tv);
            }
            discovered = found;
            message = found.length() == 0
                    ? "TV를 못 찾았어요. TV가 켜져 있고 같은 와이파이인지 확인하세요"
                    : "TV " + found.length() + "대를 찾았어요. 눌러서 선택하세요";
        });
    }

    private static final Pattern IPV4 = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)$");

    static void sendMagicPacket(String mac, String host) throws Exception {
        String hex = mac.replaceAll("[:.\\-]", "");
        if (hex.length() != 12) throw new IllegalArgumentException("MAC 주소 형식이 잘못되었습니다: " + mac);
        byte[] packet = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) packet[i] = (byte) 0xff;
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 6; j++) {
                packet[6 + i * 6 + j] = (byte) Integer.parseInt(hex.substring(j * 2, j * 2 + 2), 16);
            }
        }
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setBroadcast(true);
            sock.send(new DatagramPacket(packet, packet.length, InetAddress.getByName("255.255.255.255"), 9));
            // 일부 공유기는 전체 브로드캐스트를 막으므로 서브넷 브로드캐스트(x.x.x.255)로도 보낸다
            Matcher m = IPV4.matcher(host);
            if (m.matches()) {
                String subnet = m.group(1) + "." + m.group(2) + "." + m.group(3) + ".255";
                sock.send(new DatagramPacket(packet, packet.length, InetAddress.getByName(subnet), 9));
            }
        }
    }
}
