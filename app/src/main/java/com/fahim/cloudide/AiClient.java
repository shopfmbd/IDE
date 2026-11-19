package com.fahim.cloudide;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLException;

/**
 * Streams one chat reply from a model provider and fetches model lists. Plain java.net, no Android classes, so it is
 * tested against a local mock server (toolchain/test/com/fahim/cloudide/AiClientTest.java). Run {@link #stream} on a
 * worker thread; the listener is called on that thread, so the UI must hop to the main thread itself.
 */
final class AiClient {
    /** Exactly one of onDone / onError ends a reply, unless it was cancelled (then neither is called). */
    interface Listener {
        void onText(String delta);

        void onThinking();

        void onDone(String stopReason);

        void onError(String message);
    }

    /** An error whose message is already written for a person. */
    static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;

        Failure(String message) { super(message); }
    }

    private volatile boolean cancelled;
    private volatile HttpURLConnection conn;

    /**
     * Stops a running {@link #stream}. Safe to call from the main thread: the flag is set at once and the socket is closed on a
     * helper thread, because closing an HTTPS connection writes to the network, which Android forbids on the main thread.
     */
    void cancel() {
        cancelled = true;
        final HttpURLConnection c = conn;
        if (c == null) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    c.disconnect();
                } catch (RuntimeException ignored) {
                    // already closed
                }
            }
        }, "ai-cancel");
        t.setDaemon(true);
        t.start();
    }

    boolean isCancelled() { return cancelled; }

    void stream(final AiProtocol.Kind kind, AiProtocol.Request r, final Listener l) {
        final boolean[] finished = {false};
        final boolean[] gotText = {false};
        final String[] stop = {null};
        HttpURLConnection c = null;
        try {
            c = open(r);
            conn = c;
            if (cancelled) return; // cancel() can arrive before the connection exists
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                l.onError(redirect(c, code));
                return;
            }
            if (code >= 400) {
                l.onError(AiProtocol.httpError(code, readAll(c.getErrorStream(), 65536)));
                return;
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            AiProtocol.Sse sse = new AiProtocol.Sse(new AiProtocol.Sse.Sink() {
                @Override
                public void event(String name, String data) {
                    if (finished[0] || cancelled) return;
                    AiProtocol.Chunk ch = AiProtocol.decode(kind, name, data);
                    if (ch.error != null) {
                        finished[0] = true;
                        l.onError(ch.error);
                        return;
                    }
                    if (ch.stop != null) stop[0] = ch.stop;
                    if (!ch.text.isEmpty()) {
                        gotText[0] = true;
                        l.onText(ch.text);
                    } else if (ch.thinking) {
                        l.onThinking();
                    }
                    if (ch.done) {
                        finished[0] = true;
                        l.onDone(stop[0]);
                    }
                }
            });
            String line;
            while (!finished[0] && !cancelled && (line = in.readLine()) != null) sse.line(line);
            if (!finished[0] && !cancelled) sse.finish();
            if (!finished[0] && !cancelled) {
                finished[0] = true;
                if (gotText[0]) l.onDone(stop[0] != null ? stop[0] : "incomplete");
                else l.onError("The provider closed the connection without a reply. Check the model name and try again.");
            }
        } catch (IOException e) {
            if (!cancelled && !finished[0]) l.onError(describe(e));
        } finally {
            if (c != null) c.disconnect();
            conn = null;
        }
    }

    /** Blocking request that returns the whole response body (the model list). Throws {@link Failure} with a readable message. */
    static String fetch(AiProtocol.Request r) throws IOException {
        HttpURLConnection c = null;
        try {
            c = open(r);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) throw new Failure(redirect(c, code));
            if (code >= 400) throw new Failure(AiProtocol.httpError(code, readAll(c.getErrorStream(), 65536)));
            return readAll(c.getInputStream(), 4000000);
        } catch (Failure f) {
            throw f;
        } catch (IOException e) {
            throw new Failure(describe(e));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Uploads a file to Claude's Files API (multipart) and returns its file id. Blocking, so call it on a worker thread.
     * Throws {@link Failure} with a readable message.
     */
    static String upload(String url, String key, java.io.File file, String mime) throws IOException {
        String u = url.toLowerCase(Locale.ROOT);
        if (!u.startsWith("https://") && !u.startsWith("http://")) throw new Failure("The address must start with https://");
        HttpURLConnection c = null;
        try {
            String boundary = "----cloudide" + Long.toHexString(System.nanoTime());
            byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + file.getName().replace("\"", "") + "\"\r\n"
                    + "Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new java.net.URI(url.trim()).toURL().openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(120000);
            c.setInstanceFollowRedirects(false); // never forward the API key to another address
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "CloudIDE");
            c.setRequestProperty("x-api-key", key);
            c.setRequestProperty("anthropic-version", AiProtocol.ANTHROPIC_VERSION);
            c.setRequestProperty("anthropic-beta", "files-api-2025-04-14");
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(head.length + file.length() + tail.length); // the zip is streamed from disk, not held in memory
            try (OutputStream o = c.getOutputStream(); java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                o.write(head);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                o.write(tail);
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) throw new Failure(redirect(c, code));
            if (code >= 400) throw new Failure(AiProtocol.httpError(code, readAll(c.getErrorStream(), 65536)));
            Object j = MiniJson.tryParse(readAll(c.getInputStream(), 65536));
            String id = j == null ? null : MiniJson.str(j, "id");
            if (id == null || id.isEmpty()) throw new Failure("The provider accepted the zip but did not return a file id.");
            return id;
        } catch (Failure f) {
            throw f;
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            throw new Failure("The address is not valid: " + url);
        } catch (IOException e) {
            throw new Failure(describe(e));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static HttpURLConnection open(AiProtocol.Request r) throws IOException {
        String u = r.url.toLowerCase(Locale.ROOT);
        if (!u.startsWith("https://") && !u.startsWith("http://"))
            throw new Failure("The address must start with https:// (or http:// for a model on this device).");
        URL url;
        try {
            url = new java.net.URI(r.url.trim()).toURL();
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            throw new Failure("The address is not valid: " + r.url);
        }
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(r.method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000); // a model can think for a while before the first token
        c.setInstanceFollowRedirects(false); // never forward the API key to another address
        c.setUseCaches(false);
        c.setRequestProperty("Accept-Encoding", "identity"); // a compressed stream can be held back until it is full
        c.setRequestProperty("User-Agent", "CloudIDE");
        for (Map.Entry<String, String> h : r.headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        if (r.body != null) {
            byte[] b = r.body.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true); // no streaming mode: the body is small, and a streamed POST loses the error body of a 401 on some runtimes
            OutputStream o = c.getOutputStream();
            try {
                o.write(b);
            } finally {
                o.close();
            }
        }
        return c;
    }

    private static String readAll(InputStream in, int max) throws IOException {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < max) out.write(buf, 0, Math.min(n, max - out.size()));
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static String redirect(HttpURLConnection c, int code) {
        String to = c.getHeaderField("Location");
        return "The server answered with a redirect (HTTP " + code + (to == null ? "" : " to " + to) + "). Set the base URL in Settings > AI assistant to the address it points to; "
                + "a missing \"s\" in https:// is the usual cause.";
    }

    /** A person-readable sentence for a failure below HTTP (no network, bad certificate, timeout...). */
    static String describe(IOException e) {
        if (e instanceof Failure) return e.getMessage();
        String m = String.valueOf(e.getMessage());
        if (e instanceof UnknownHostException) return "Cannot reach the server (" + m + "). Check your internet connection and the base URL.";
        if (e instanceof SocketTimeoutException) return "The server did not answer in time. Check your connection and try again.";
        if (e instanceof ConnectException) return "Could not connect (" + m + "). Check the base URL, and that the server is running if it is on your network.";
        if (e instanceof SSLException) return "The secure connection failed (" + m + "). Check the base URL and that the date and time on this device are right.";
        if (e instanceof UnknownServiceException || m.contains("CLEARTEXT"))
            return "Plain http:// is only allowed for localhost. Use https:// for any other server.";
        return e.getClass().getSimpleName() + ": " + m;
    }
}
