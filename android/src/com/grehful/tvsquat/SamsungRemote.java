package com.grehful.tvsquat;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 삼성 스마트 TV(2016년 이후 Tizen) 원격 제어 - samsungtvws 파이썬 라이브러리와 같은 프로토콜.
 *
 * - wss://TV:8002/api/v2/channels/samsung.remote.control 웹소켓으로 리모컨 키 전송
 *   (처음 연결하면 TV 화면에 허용 팝업이 뜨고, 허용하면 토큰을 받아 다음부터 팝업 없이 연결)
 * - http://TV:8001/api/v2/ 로 기기 정보와 전원 상태(PowerState) 확인
 */
final class SamsungRemote {
    static final class Unauthorized extends IOException {
        Unauthorized(String msg) { super(msg); }
    }

    private static final String APP_NAME = "TV Squat";

    private SamsungRemote() {}

    /** 기기 정보. 꺼져 있거나 응답이 없으면 null. */
    static JSONObject deviceInfo(String host, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://" + host + ":8001/api/v2/").openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            if (conn.getResponseCode() != 200) return null;
            return new JSONObject(new String(readAll(conn.getInputStream()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** "on", "standby" 또는 null(응답 없음 = 꺼짐). PowerState가 없는 구형 모델은 응답하면 "on". */
    static String powerState(String host, int timeoutMs) {
        JSONObject info = deviceInfo(host, timeoutMs);
        if (info == null) return null;
        JSONObject dev = info.optJSONObject("device");
        String state = dev == null ? null : dev.optString("PowerState", null);
        return state == null ? "on" : state.toLowerCase();
    }

    /**
     * 키를 보낸다. token이 없거나 틀리면 TV에 허용 팝업이 뜨므로 waitMs 동안 기다린다.
     * @return TV가 새로 발급한 토큰 (없으면 기존 token)
     */
    static String sendKey(String host, String token, String key, int waitMs) throws IOException {
        IOException first;
        try {
            return sendKeyOn(host, 8002, true, token, key, waitMs);
        } catch (Unauthorized e) {
            throw e;
        } catch (IOException e) {
            first = e;
        }
        try {
            // 2016~2017년 일부 모델은 8001 포트(암호화 없음)만 지원
            return sendKeyOn(host, 8001, false, token, key, waitMs);
        } catch (IOException e) {
            throw first;
        }
    }

    private static String sendKeyOn(String host, int port, boolean tls, String token, String key, int waitMs)
            throws IOException {
        String name = Base64.encodeToString(APP_NAME.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String path = "/api/v2/channels/samsung.remote.control?name=" + URLEncoder.encode(name, "UTF-8");
        if (token != null && !token.isEmpty()) path += "&token=" + URLEncoder.encode(token, "UTF-8");

        try (WebSocket ws = WebSocket.connect(host, port, tls, path, waitMs)) {
            String newToken = token;
            long deadline = System.currentTimeMillis() + waitMs;
            // 연결 승인 이벤트를 기다린다
            while (true) {
                if (System.currentTimeMillis() > deadline) throw new IOException("TV 응답 시간 초과 (TV 화면에서 허용을 눌렀나요?)");
                JSONObject msg;
                try {
                    msg = new JSONObject(ws.readText());
                } catch (org.json.JSONException e) {
                    continue;
                }
                String event = msg.optString("event");
                if ("ms.channel.connect".equals(event)) {
                    JSONObject data = msg.optJSONObject("data");
                    if (data != null && data.has("token")) newToken = data.optString("token");
                    break;
                }
                if ("ms.channel.unauthorized".equals(event)) throw new Unauthorized("TV에서 연결을 거부했어요");
                if ("ms.channel.timeOut".equals(event)) throw new IOException("TV 허용 팝업 시간 초과");
            }
            JSONObject params = new JSONObject();
            JSONObject cmd = new JSONObject();
            try {
                params.put("Cmd", "Click");
                params.put("DataOfCmd", key);
                params.put("Option", "false");
                params.put("TypeOfRemote", "SendRemoteKey");
                cmd.put("method", "ms.remote.control");
                cmd.put("params", params);
            } catch (org.json.JSONException e) {
                throw new IOException(e);
            }
            ws.sendText(cmd.toString());
            try {
                Thread.sleep(500); // 바로 끊으면 키가 무시되는 모델이 있음
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return newToken;
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** 최소한의 웹소켓 클라이언트 (텍스트 프레임만). */
    static final class WebSocket implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final SecureRandom random = new SecureRandom();

        private WebSocket(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        static WebSocket connect(String host, int port, boolean tls, String path, int readTimeoutMs) throws IOException {
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(host, port), 3000);
            raw.setSoTimeout(readTimeoutMs);
            Socket socket = raw;
            if (tls) {
                // TV는 자체 서명 인증서를 쓰므로 이 연결(집 안의 TV)에 한해서만 인증서 검사를 생략한다
                SSLSocket ssl = (SSLSocket) trustAllContext().getSocketFactory().createSocket(raw, host, port, true);
                ssl.startHandshake();
                socket = ssl;
            }
            WebSocket ws = new WebSocket(socket);
            try {
                ws.handshake(host, port, path);
            } catch (IOException e) {
                ws.close();
                throw e;
            }
            return ws;
        }

        private static SSLContext trustAllContext() throws IOException {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }}, new SecureRandom());
                return ctx;
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        private void handshake(String host, int port, String path) throws IOException {
            byte[] nonce = new byte[16];
            random.nextBytes(nonce);
            String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            // 응답 헤더를 빈 줄까지 읽는다
            StringBuilder head = new StringBuilder();
            while (!head.toString().endsWith("\r\n\r\n")) {
                int b = in.read();
                if (b < 0) throw new EOFException("웹소켓 핸드셰이크 실패");
                head.append((char) b);
                if (head.length() > 16384) throw new IOException("웹소켓 응답이 너무 깁니다");
            }
            String status = head.substring(0, head.indexOf("\r\n"));
            if (!status.contains(" 101")) throw new IOException("웹소켓 연결 실패: " + status);
        }

        void sendText(String text) throws IOException {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81); // FIN + 텍스트
            int len = payload.length;
            if (len < 126) {
                frame.write(0x80 | len);
            } else if (len < 65536) {
                frame.write(0x80 | 126);
                frame.write(len >> 8);
                frame.write(len);
            } else {
                frame.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) frame.write((int) ((long) len >> (8 * i)));
            }
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            frame.write(mask);
            for (int i = 0; i < len; i++) frame.write(payload[i] ^ mask[i % 4]);
            out.write(frame.toByteArray());
            out.flush();
        }

        private void sendControl(int opcode, byte[] payload) throws IOException {
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x80 | opcode);
            frame.write(0x80 | payload.length);
            frame.write(mask);
            for (int i = 0; i < payload.length; i++) frame.write(payload[i] ^ mask[i % 4]);
            out.write(frame.toByteArray());
            out.flush();
        }

        /** 다음 텍스트 메시지. ping에는 pong으로 답하고, close면 EOFException. */
        String readText() throws IOException {
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            while (true) {
                int b0 = readByte(), b1 = readByte();
                int opcode = b0 & 0x0f;
                long len = b1 & 0x7f;
                if (len == 126) len = (readByte() << 8) | readByte();
                else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
                }
                if (len > (1 << 20)) throw new IOException("웹소켓 메시지가 너무 큽니다");
                byte[] mask = null;
                if ((b1 & 0x80) != 0) {
                    mask = new byte[4];
                    for (int i = 0; i < 4; i++) mask[i] = (byte) readByte();
                }
                byte[] payload = new byte[(int) len];
                for (int i = 0; i < len; i++) {
                    payload[i] = (byte) readByte();
                    if (mask != null) payload[i] ^= mask[i % 4];
                }
                if (opcode == 0x8) throw new EOFException("TV가 연결을 닫았어요");
                if (opcode == 0x9) {
                    sendControl(0xA, payload);
                    continue;
                }
                if (opcode == 0xA) continue;
                message.write(payload);
                if ((b0 & 0x80) != 0) return new String(message.toByteArray(), StandardCharsets.UTF_8);
            }
        }

        private int readByte() throws IOException {
            int b = in.read();
            if (b < 0) throw new EOFException("연결이 끊겼어요");
            return b;
        }

        @Override
        public void close() {
            try {
                sendControl(0x8, new byte[0]);
            } catch (IOException ignored) {
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
