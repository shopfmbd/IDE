package com.fahim.cloudide;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Talks to a sandbox server (see sandbox-server/server.py): run commands, list, read, write, upload, unzip and download files in the
 * workspace the server keeps for this session. Plain JDK (HttpURLConnection + {@link MiniJson}), so it is tested on a plain JVM
 * against a small fake server.
 *
 * <p>The token travels in an Authorization header; with a plain http:// address it is visible on the network, so Settings tells the person to use
 * https. Every call has a time limit, and a reply bigger than {@link #MAX_REPLY} is refused.
 */
final class SandboxClient {
    static final long MAX_REPLY = 120L * 1024 * 1024;

    private final String base;
    private final String token, session;

    SandboxClient(String baseUrl, String token, String session) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
        this.token = token == null ? "" : token.trim();
        this.session = session == null || session.isEmpty() ? "default" : session;
    }

    /** Null when the address looks usable, else why not. */
    static String checkUrl(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(java.util.Locale.ROOT);
        if (u.isEmpty()) return "Enter the sandbox server address.";
        if (!u.startsWith("http://") && !u.startsWith("https://")) return "The sandbox address must start with https:// (or http://).";
        return null;
    }

    private static String q(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpURLConnection open(String method, String path, int readTimeoutSec) throws IOException {
        String err = checkUrl(base);
        if (err != null) throw new IOException(err);
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(Math.max(10, readTimeoutSec) * 1000);
        c.setInstanceFollowRedirects(false);
        if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("X-Session", session);
        c.setRequestProperty("User-Agent", "CloudIDE-sandbox");
        return c;
    }

    private static String drain(InputStream in, long max) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (b.size() + n > max) throw new IOException("The server's answer is too large.");
            b.write(buf, 0, n);
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static IOException failure(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        String body;
        try {
            body = drain(c.getErrorStream(), 20000);
        } catch (IOException e) {
            body = "";
        }
        String msg = MiniJson.str(MiniJson.tryParse(body), "error");
        if (code == 401 || code == 403) return new IOException("The sandbox server refused the token (HTTP " + code + "). Check the token in Settings.");
        if (code == 404) return new IOException("The sandbox server has no such endpoint or file (HTTP 404)" + (msg == null ? "" : ": " + msg));
        return new IOException("Sandbox server error " + code + (msg != null ? ": " + msg : body.isEmpty() ? "" : ": " + body.substring(0, Math.min(200, body.length()))));
    }

    private Object json(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code / 100 != 2) throw failure(c);
        String body;
        try (InputStream in = c.getInputStream()) {
            body = drain(in, MAX_REPLY);
        }
        Object v = MiniJson.tryParse(body);
        if (!(v instanceof Map)) throw new IOException("The sandbox server did not answer with JSON. Is the address right?");
        return v;
    }

    private Object post(String path, Map<String, Object> body, int readTimeoutSec) throws IOException {
        HttpURLConnection c = open("POST", path, readTimeoutSec);
        try {
            byte[] data = MiniJson.write(body).getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(data);
            }
            return json(c);
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------ calls
    /** Asks the server if it is there and the token is right. Returns the server's short description. */
    String health() throws IOException {
        HttpURLConnection c = open("GET", "/health", 20);
        try {
            Object v = json(c);
            String info = MiniJson.str(v, "info");
            return info == null ? "ok" : info;
        } finally {
            c.disconnect();
        }
    }

    SandboxFiles.Exec exec(String command, String cwd, int timeoutSec) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("command", command);
        if (cwd != null && !cwd.isEmpty()) m.put("cwd", cwd);
        m.put("timeout", (long) timeoutSec);
        Object v = post("/exec", m, timeoutSec + 20);
        Object code = MiniJson.at(v, "code");
        Object to = MiniJson.at(v, "timed_out");
        String out = MiniJson.str(v, "out");
        return new SandboxFiles.Exec(out, code instanceof Number ? ((Number) code).intValue() : 1, Boolean.TRUE.equals(to));
    }

    String list(String path, int depth) throws IOException {
        HttpURLConnection c = open("GET", "/list?path=" + q(path) + "&depth=" + depth, 60);
        try {
            String t = MiniJson.str(json(c), "text");
            return t == null ? "" : t;
        } finally {
            c.disconnect();
        }
    }

    /** The file's text (cut by the server at max characters). */
    String read(String path, int max) throws IOException {
        HttpURLConnection c = open("GET", "/read?path=" + q(path) + "&max=" + max, 60);
        try {
            Object v = json(c);
            String t = MiniJson.str(v, "text");
            return t == null ? "" : t;
        } finally {
            c.disconnect();
        }
    }

    void upload(String path, File file) throws IOException {
        HttpURLConnection c = open("PUT", "/file?path=" + q(path), 600);
        try {
            long len = file.length();
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setFixedLengthStreamingMode(len);
            try (InputStream in = new FileInputStream(file); OutputStream os = c.getOutputStream()) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            json(c);
        } finally {
            c.disconnect();
        }
    }

    void write(String path, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = open("PUT", "/file?path=" + q(path), 120);
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(data);
            }
            json(c);
        } finally {
            c.disconnect();
        }
    }

    /** Unpacks a zip that is already on the server. Returns the server's summary ("12 files, 3 folders, 40 KB"). */
    String unzip(String zipPath, String to) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", zipPath);
        m.put("to", to);
        String s = MiniJson.str(post("/unzip", m, 300), "summary");
        return s == null ? "done" : s;
    }

    /** Downloads a file as it is, or a folder as a zip, into {@code out}. */
    void download(String path, File out) throws IOException {
        HttpURLConnection c = open("GET", "/download?path=" + q(path), 600);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw failure(c);
            long total = 0;
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_REPLY) throw new IOException("The download is larger than " + SandboxFiles.size(MAX_REPLY) + ".");
                    os.write(buf, 0, n);
                }
            } catch (IOException e) {
                out.delete();
                throw e;
            }
        } finally {
            c.disconnect();
        }
    }

    /** Empties the workspace. */
    void reset() throws IOException {
        HttpURLConnection c = open("POST", "/reset", 60);
        try {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            json(c);
        } finally {
            c.disconnect();
        }
    }
}
