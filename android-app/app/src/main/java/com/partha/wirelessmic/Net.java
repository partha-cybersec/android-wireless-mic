package com.partha.wirelessmic;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;

/** Minimal HTTP + WebSocket server and WebSocket client (RFC 6455), no dependencies. */
final class Net {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** HTTP response for a GET: either bytes or a file to stream. */
    static final class Response {
        final String type; final byte[] body; final InputStream stream; final long length; final String filename;
        Response(String type, byte[] body) { this(type, body, null, body.length, null); }
        Response(String type, File file) throws IOException { this(type, null, new FileInputStream(file), file.length(), file.getName()); }
        Response(String type, InputStream stream, long length, String filename) { this(type, null, stream, length, filename); }
        private Response(String type, byte[] body, InputStream stream, long length, String filename) {
            this.type = type; this.body = body; this.stream = stream; this.length = length; this.filename = filename;
        }
    }

    interface Routes { Response get(String path); }

    interface Listener {
        void onOpen(Session s);
        void onText(Session s, String text);
        void onClose(Session s);
    }

    /** One connected WebSocket peer; outgoing frames are queued so a slow peer never blocks audio. */
    static final class Session {
        final Socket sock;
        private final OutputStream out;
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(48);
        private volatile boolean open = true;

        Session(Socket sock, OutputStream out) {
            this.sock = sock; this.out = out;
            new Thread(this::writer, "ws-writer").start();
        }

        String remoteIp() { return sock.getInetAddress().getHostAddress(); }
        boolean isLocal() { return sock.getInetAddress().isLoopbackAddress(); }
        void sendText(String s) { offer(frame(0x1, s.getBytes(StandardCharsets.UTF_8), false)); }
        void sendBinary(byte[] b) { offer(frame(0x2, b, false)); }

        private void offer(byte[] f) {
            if (!open) return;
            while (!queue.offer(f)) queue.poll();          // drop oldest audio if the peer lags
        }

        private void writer() {
            try {
                while (open) {
                    byte[] f = queue.take();
                    out.write(f);
                    if (queue.isEmpty()) out.flush();
                }
            } catch (Exception e) { close(); }
        }

        void close() {
            open = false;
            queue.offer(new byte[0]);
            try { sock.close(); } catch (IOException ignored) { }
        }
    }

    /** HTTP + WebSocket server on one or more ports. */
    static final class Server {
        final Set<Session> sessions = new CopyOnWriteArraySet<>();
        private final Routes routes;
        private final Listener listener;
        private final java.util.List<ServerSocket> sockets = new java.util.ArrayList<>();

        Server(Routes routes, Listener listener) { this.routes = routes; this.listener = listener; }

        void listen(int port) throws IOException {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
            sockets.add(ss);
            new Thread(() -> {
                while (!ss.isClosed()) {
                    try {
                        Socket s = ss.accept();
                        s.setTcpNoDelay(true);
                        new Thread(() -> handle(s), "http-conn").start();
                    } catch (IOException e) { break; }
                }
            }, "http-accept-" + port).start();
        }

        void broadcastBinary(byte[] b) { for (Session s : sessions) s.sendBinary(b); }
        void broadcastText(String t) { for (Session s : sessions) s.sendText(t); }

        void stop() {
            for (ServerSocket ss : sockets) try { ss.close(); } catch (IOException ignored) { }
            for (Session s : sessions) s.close();
            sessions.clear();
        }

        private void handle(Socket sock) {
            try {
                InputStream in = new BufferedInputStream(sock.getInputStream());
                OutputStream out = sock.getOutputStream();
                String requestLine = readLine(in);
                if (requestLine == null) { sock.close(); return; }
                Map<String, String> h = readHeaders(in);
                String path = requestLine.split(" ").length > 1 ? requestLine.split(" ")[1] : "/";
                if ("websocket".equalsIgnoreCase(h.get("upgrade"))) {
                    String accept = Base64.encodeToString(
                            MessageDigest.getInstance("SHA-1").digest((h.get("sec-websocket-key") + GUID).getBytes()),
                            Base64.NO_WRAP);
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                            + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes());
                    out.flush();
                    Session s = new Session(sock, out);
                    sessions.add(s);
                    listener.onOpen(s);
                    try {
                        readFrames(in, s, true);
                    } finally {
                        sessions.remove(s);
                        s.close();
                        listener.onClose(s);
                    }
                } else {
                    serveHttp(out, path);
                    sock.close();
                }
            } catch (Exception e) {
                try { sock.close(); } catch (IOException ignored) { }
            }
        }

        private void serveHttp(OutputStream out, String path) throws IOException {
            Response r = routes.get(path);
            if (r == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());
                return;
            }
            String extra = r.filename != null ? "Content-Disposition: attachment; filename=\"" + r.filename + "\"\r\n" : "";
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + r.type + "\r\nContent-Length: " + r.length
                    + "\r\nCache-Control: no-store\r\n" + extra + "Connection: close\r\n\r\n").getBytes());
            if (r.body != null) out.write(r.body);
            else try (InputStream fi = r.stream) {
                byte[] b = new byte[65536];
                for (int n; (n = fi.read(b)) > 0; ) out.write(b, 0, n);
            }
            out.flush();
        }

        private void readFrames(InputStream in, Session s, boolean expectMasked) throws IOException {
            Net.readFrames(in, (op, data) -> {
                if (op == 0x1) listener.onText(s, new String(data, StandardCharsets.UTF_8));
                else if (op == 0x8) throw new EOFException();
                else if (op == 0x9) s.offer(frame(0xA, data, false));
            });
        }
    }

    /** WebSocket client used by the receiver. */
    static final class Client {
        interface Callback {
            void onBinary(byte[] data);
            void onText(String text);
            void onClosed(String why);
        }

        private final Socket sock = new Socket();
        private OutputStream out;
        private volatile boolean closed;

        void connect(String host, int port, Callback cb) {
            new Thread(() -> {
                try {
                    sock.connect(new InetSocketAddress(host, port), 4000);
                    sock.setTcpNoDelay(true);
                    sock.setReceiveBufferSize(64 * 1024);
                    out = sock.getOutputStream();
                    byte[] keyBytes = new byte[16];
                    new SecureRandom().nextBytes(keyBytes);
                    String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
                    out.write(("GET / HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nUpgrade: websocket\r\n"
                            + "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes());
                    out.flush();
                    InputStream in = new BufferedInputStream(sock.getInputStream());
                    String status = readLine(in);
                    if (status == null || !status.contains(" 101 ")) throw new IOException("handshake failed: " + status);
                    readHeaders(in);
                    readFrames(in, (op, data) -> {
                        if (op == 0x2) cb.onBinary(data);
                        else if (op == 0x1) cb.onText(new String(data, StandardCharsets.UTF_8));
                        else if (op == 0x8) throw new EOFException();
                    });
                } catch (Exception e) {
                    if (!closed) cb.onClosed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                } finally {
                    close();
                }
            }, "ws-client").start();
        }

        synchronized void sendText(String t) {
            if (out == null || closed) return;
            try { out.write(frame(0x1, t.getBytes(StandardCharsets.UTF_8), true)); out.flush(); }
            catch (IOException e) { close(); }
        }

        void close() {
            closed = true;
            try { sock.close(); } catch (IOException ignored) { }
        }
    }

    // ---- framing helpers ----------------------------------------------------------------------

    interface FrameSink { void onFrame(int opcode, byte[] payload) throws IOException; }

    static void readFrames(InputStream raw, FrameSink sink) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        while (true) {
            int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
            int op = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) len = in.readUnsignedShort();
            else if (len == 127) len = in.readLong();
            if (len > 8 * 1024 * 1024) throw new IOException("frame too large");
            byte[] mask = new byte[4];
            if (masked) in.readFully(mask);
            byte[] data = new byte[(int) len];
            in.readFully(data);
            if (masked) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
            sink.onFrame(op, data);
        }
    }

    static byte[] frame(int opcode, byte[] payload, boolean mask) {
        int n = payload.length;
        int head = 2 + (n < 126 ? 0 : (n < 65536 ? 2 : 8)) + (mask ? 4 : 0);
        byte[] f = new byte[head + n];
        f[0] = (byte) (0x80 | opcode);
        int p;
        if (n < 126) { f[1] = (byte) n; p = 2; }
        else if (n < 65536) { f[1] = 126; f[2] = (byte) (n >> 8); f[3] = (byte) n; p = 4; }
        else { f[1] = 127; for (int i = 0; i < 8; i++) f[2 + i] = (byte) ((long) n >> (56 - 8 * i)); p = 10; }
        if (mask) {
            f[1] |= (byte) 0x80;
            byte[] m = new byte[4];
            new SecureRandom().nextBytes(m);
            System.arraycopy(m, 0, f, p, 4);
            p += 4;
            for (int i = 0; i < n; i++) f[p + i] = (byte) (payload[i] ^ m[i & 3]);
        } else {
            System.arraycopy(payload, 0, f, p, n);
        }
        return f;
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        return c == -1 && sb.length() == 0 ? null : sb.toString();
    }

    static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> h = new HashMap<>();
        for (String line; (line = readLine(in)) != null && !line.isEmpty(); ) {
            int i = line.indexOf(':');
            if (i > 0) h.put(line.substring(0, i).trim().toLowerCase(Locale.US), line.substring(i + 1).trim());
        }
        return h;
    }

    private Net() {}
}
