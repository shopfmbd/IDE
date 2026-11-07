package com.fahim.cloudide;

import com.fahim.cloudide.AiProtocol.Kind;
import com.fahim.cloudide.AiProtocol.Msg;
import com.fahim.cloudide.AiProtocol.Provider;
import com.fahim.cloudide.AiProtocol.Request;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Plain-JDK test for AiClient against a local mock server that speaks each provider's streaming format. */
public class AiClientTest {
    static int fails = 0, passes = 0;

    static void check(String n, boolean ok, String d) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + n + "  -> " + d); }
    }

    /** Collects what the listener hears. */
    static class Rec implements AiClient.Listener {
        final StringBuilder text = new StringBuilder();
        final List<String> terminal = Collections.synchronizedList(new ArrayList<String>());
        volatile boolean thinking;
        final CountDownLatch firstText = new CountDownLatch(1), ended = new CountDownLatch(1);

        public void onText(String d) { text.append(d); firstText.countDown(); }
        public void onThinking() { thinking = true; }
        public void onDone(String s) { terminal.add("done:" + s); ended.countDown(); }
        public void onError(String m) { terminal.add("error:" + m); ended.countDown(); }
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }

    static void sse(HttpExchange ex, String... pieces) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        for (String p : pieces) {
            os.write(p.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        os.close();
    }

    static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static Rec run(Kind kind, Request r) throws Exception {
        final Rec rec = new Rec();
        final AiClient c = new AiClient();
        Thread t = new Thread(new Runnable() { public void run() { c.stream(kind, r, rec); } });
        t.start();
        check("stream finishes", rec.ended.await(15, TimeUnit.SECONDS), "timeout");
        t.join(5000);
        return rec;
    }

    public static void main(String[] args) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        srv.setExecutor(Executors.newCachedThreadPool());
        final AtomicReference<String> seenPath = new AtomicReference<>(), seenBody = new AtomicReference<>();
        final AtomicReference<Map<String, String>> seenHeaders = new AtomicReference<>();
        final CountDownLatch cancelSeen = new CountDownLatch(1);

        srv.createContext("/", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException {
                String path = ex.getRequestURI().toString();
                seenPath.set(ex.getRequestMethod() + " " + path);
                Map<String, String> h = new HashMap<>();
                for (String k : ex.getRequestHeaders().keySet()) h.put(k.toLowerCase(Locale.ROOT), ex.getRequestHeaders().getFirst(k));
                seenHeaders.set(h);
                seenBody.set(read(ex.getRequestBody()));
                if (path.startsWith("/claude/v1/messages")) {
                    sse(ex, "event: message_start\ndata: {\"type\":\"message_start\"}\n\n",
                            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel",   // a line split across writes
                            "lo\"}}\n\n",
                            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\" \u09ec\"}}\n\n",
                            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}\n\n",
                            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n");
                } else if (path.startsWith("/oa/chat/completions")) {
                    sse(ex, "data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n", "data: {\"choices\":[{\"delta\":{\"content\":\"B\"},\"finish_reason\":\"stop\"}]}\n\n", "data: [DONE]\n\n");
                } else if (path.startsWith("/gem/v1beta/models/m:streamGenerateContent")) {
                    sse(ex, "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"G1\"}]}}]}\n\n",
                            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"G2\"}]},\"finishReason\":\"STOP\"}]}\n\n");
                } else if (path.startsWith("/denied/")) {
                    reply(ex, 401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");
                } else if (path.startsWith("/moved/")) {
                    ex.getResponseHeaders().set("Location", "https://elsewhere.example/v1/chat/completions");
                    ex.sendResponseHeaders(301, -1);
                    ex.close();
                } else if (path.startsWith("/dropped-with-text/")) {
                    sse(ex, "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n");           // no [DONE], no finish_reason
                } else if (path.startsWith("/dropped-empty/")) {
                    sse(ex);
                } else if (path.startsWith("/midstream-error/")) {
                    sse(ex, "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"so far\"}}\n\n",
                            "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n");
                } else if (path.startsWith("/slow/")) {
                    ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                    ex.sendResponseHeaders(200, 0);
                    OutputStream os = ex.getResponseBody();
                    try {
                        os.write("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        for (int i = 0; i < 100; i++) { Thread.sleep(50); os.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8)); os.flush(); }
                    } catch (Exception gone) {
                        cancelSeen.countDown();          // the client hung up
                    }
                    ex.close();
                } else if (path.startsWith("/many/")) {
                    StringBuilder b = new StringBuilder();
                    for (int i = 0; i < 3000; i++) b.append("data: {\"choices\":[{\"delta\":{\"content\":\"").append(i % 10).append("\"}}]}\n\n");
                    b.append("data: [DONE]\n\n");
                    sse(ex, b.toString());
                } else if (path.startsWith("/models/models")) {
                    reply(ex, 200, "{\"data\":[{\"id\":\"gpt-5.5\"},{\"id\":\"whisper-1\"}]}");
                } else if (path.startsWith("/denied-models/models")) {
                    reply(ex, 403, "{\"error\":{\"message\":\"nope\"}}");
                } else {
                    reply(ex, 404, "{\"error\":{\"message\":\"no such route " + path + "\"}}");
                }
            }
        });
        srv.start();
        String host = "http://127.0.0.1:" + srv.getAddress().getPort();
        List<Msg> hi = Arrays.asList(new Msg("user", "Hi \"there\""));
        Provider claude = AiProtocol.provider("anthropic"), gemini = AiProtocol.provider("gemini"), custom = AiProtocol.provider("custom");

        // ---- Claude
        Rec r = run(Kind.ANTHROPIC, AiProtocol.chat(claude, host + "/claude", "sk-ant-x", "claude-sonnet-5-5", "sys", hi, 1000));
        check("claude: route, method", "POST /claude/v1/messages".equals(seenPath.get()), seenPath.get());
        check("claude: key + version headers reach the server", "sk-ant-x".equals(seenHeaders.get().get("x-api-key")) && "2023-06-01".equals(seenHeaders.get().get("anthropic-version")), "" + seenHeaders.get());
        check("claude: body arrives as valid JSON with the model and message", "claude-sonnet-5-5".equals(MiniJson.str(MiniJson.parse(seenBody.get()), "model")) && "Hi \"there\"".equals(MiniJson.str(MiniJson.parse(seenBody.get()), "messages", 0, "content")), seenBody.get());
        check("claude: a line split across TCP writes is reassembled, unicode intact", "Hello \u09ec".equals(r.text.toString()), r.text.toString());
        check("claude: ends once, with the stop reason", r.terminal.equals(Arrays.asList("done:max_tokens")), "" + r.terminal);
        check("claude: identity encoding requested so nothing buffers", "identity".equals(seenHeaders.get().get("accept-encoding")), "" + seenHeaders.get());

        // ---- OpenAI-compatible
        r = run(Kind.OPENAI, AiProtocol.chat(custom, host + "/oa", "sk-o", "m", "sys", hi, 1000));
        check("openai: route and bearer", "POST /oa/chat/completions".equals(seenPath.get()) && "Bearer sk-o".equals(seenHeaders.get().get("authorization")), seenPath.get() + " " + seenHeaders.get());
        check("openai: text and single completion", "AB".equals(r.text.toString()) && r.terminal.equals(Arrays.asList("done:stop")), r.text + " " + r.terminal);

        // ---- Gemini
        r = run(Kind.GEMINI, AiProtocol.chat(gemini, host + "/gem", "AIza-k", "m", "sys", hi, 1000));
        check("gemini: route with alt=sse and key header", "POST /gem/v1beta/models/m:streamGenerateContent?alt=sse".equals(seenPath.get()) && "AIza-k".equals(seenHeaders.get().get("x-goog-api-key")), seenPath.get() + " " + seenHeaders.get());
        check("gemini: text and finishReason", "G1G2".equals(r.text.toString()) && r.terminal.equals(Arrays.asList("done:STOP")), r.text + " " + r.terminal);

        // ---- failures
        r = run(Kind.ANTHROPIC, AiProtocol.chat(claude, host + "/denied", "bad", "m", "", hi, 10));
        check("401: one error, readable, no text", r.terminal.size() == 1 && r.terminal.get(0).startsWith("error:HTTP 401: invalid x-api-key") && r.terminal.get(0).contains("Settings") && r.text.length() == 0, "" + r.terminal);
        r = run(Kind.OPENAI, AiProtocol.chat(custom, host + "/moved", "k", "m", "", hi, 10));
        check("redirect is reported, not followed, and says where", r.terminal.size() == 1 && r.terminal.get(0).contains("redirect") && r.terminal.get(0).contains("elsewhere.example"), "" + r.terminal);
        r = run(Kind.OPENAI, AiProtocol.chat(custom, host + "/dropped-with-text", "k", "m", "", hi, 10));
        check("connection ends after some text: kept and marked incomplete", "partial".equals(r.text.toString()) && r.terminal.equals(Arrays.asList("done:incomplete")), r.text + " " + r.terminal);
        r = run(Kind.OPENAI, AiProtocol.chat(custom, host + "/dropped-empty", "k", "m", "", hi, 10));
        check("connection ends with nothing: an error that says what to check", r.terminal.size() == 1 && r.terminal.get(0).startsWith("error:") && r.terminal.get(0).contains("model name"), "" + r.terminal);
        r = run(Kind.ANTHROPIC, AiProtocol.chat(claude, host + "/midstream-error", "k", "m", "", hi, 10));
        check("error event after some text: text kept, then exactly one error", "so far".equals(r.text.toString()) && r.terminal.equals(Arrays.asList("error:Overloaded")), r.text + " " + r.terminal);
        r = run(Kind.OPENAI, AiProtocol.chat(custom, host + "/many", "k", "m", "", hi, 10));
        StringBuilder expect = new StringBuilder();
        for (int i = 0; i < 3000; i++) expect.append(i % 10);
        check("3000 events arrive complete and in order", expect.toString().equals(r.text.toString()) && r.terminal.equals(Arrays.asList("done:null")), r.text.length() + " " + r.terminal);

        // connection refused: a port nobody listens on
        java.net.ServerSocket s = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int dead = s.getLocalPort();
        s.close();
        r = run(Kind.OPENAI, AiProtocol.chat(custom, "http://127.0.0.1:" + dead, "k", "m", "", hi, 10));
        check("connection refused: friendly sentence", r.terminal.size() == 1 && r.terminal.get(0).startsWith("error:Could not connect"), "" + r.terminal);
        r = run(Kind.OPENAI, AiProtocol.chat(custom, "ftp://example.com", "k", "m", "", hi, 10));
        check("non-http address is refused before any connection", r.terminal.size() == 1 && r.terminal.get(0).contains("https://"), "" + r.terminal);
        r = run(Kind.OPENAI, AiProtocol.chat(custom, "", "k", "m", "", hi, 10));
        check("empty address (custom provider, nothing typed) is refused with advice", r.terminal.size() == 1 && r.terminal.get(0).startsWith("error:"), "" + r.terminal);

        // ---- cancel while the server is still talking
        final Rec slow = new Rec();
        final AiClient client = new AiClient();
        final Request slowReq = AiProtocol.chat(custom, host + "/slow", "k", "m", "", hi, 10);
        Thread t = new Thread(new Runnable() { public void run() { client.stream(Kind.OPENAI, slowReq, slow); } });
        t.start();
        check("cancel: first text arrives", slow.firstText.await(10, TimeUnit.SECONDS), "");
        long t0 = System.currentTimeMillis();
        client.cancel();
        t.join(5000);
        check("cancel: the worker returns promptly", !t.isAlive() && System.currentTimeMillis() - t0 < 3000, (System.currentTimeMillis() - t0) + " ms");
        check("cancel: neither done nor error is reported", slow.terminal.isEmpty(), "" + slow.terminal);
        check("cancel: the server sees the client hang up", cancelSeen.await(5, TimeUnit.SECONDS), "");
        AiClient early = new AiClient();
        early.cancel();
        Rec er = new Rec();
        early.stream(Kind.OPENAI, AiProtocol.chat(custom, host + "/oa", "k", "m", "", hi, 10), er);
        check("cancel before start: nothing is reported", er.terminal.isEmpty() && er.text.length() == 0, "" + er.terminal);

        // ---- models list
        String body = AiClient.fetch(AiProtocol.models(custom, host + "/models", "k"));
        check("models: fetched and parsed", AiProtocol.parseModels(Kind.OPENAI, body).equals(Arrays.asList("gpt-5.5")), body);
        try {
            AiClient.fetch(AiProtocol.models(custom, host + "/denied-models", "k"));
            check("models: 403 throws", false, "no exception");
        } catch (AiClient.Failure f) {
            check("models: 403 gives a readable failure", f.getMessage().startsWith("HTTP 403: nope"), f.getMessage());
        }
        try {
            AiClient.fetch(AiProtocol.models(custom, "http://127.0.0.1:" + dead, "k"));
            check("models: refused connection throws", false, "no exception");
        } catch (AiClient.Failure f) {
            check("models: refused connection gives a readable failure", f.getMessage().startsWith("Could not connect"), f.getMessage());
        }

        srv.stop(0);
        System.out.println(passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
