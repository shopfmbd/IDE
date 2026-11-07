package com.fahim.cloudide;

import com.fahim.cloudide.AiProtocol.Block;
import com.fahim.cloudide.AiProtocol.Chunk;
import com.fahim.cloudide.AiProtocol.Kind;
import com.fahim.cloudide.AiProtocol.Msg;
import com.fahim.cloudide.AiProtocol.Provider;
import com.fahim.cloudide.AiProtocol.Request;
import java.util.*;

/** Plain-JDK test for AiProtocol: requests, streamed replies, errors, prompt building, secret masking, Markdown blocks. */
public class AiProtocolTest {
    static int fails = 0, passes = 0;

    static boolean secretMasked() {
        AiProtocol.Context c = new AiProtocol.Context();
        AiProtocol.addCode(c, "app/Api.kt", "val k = \"sk-ant-abcdefghijklmnopqrstuvwxyz0123456789\"");
        String p = AiProtocol.compose(c);
        return p.contains("[secret removed]") && !p.contains("sk-ant-abcdef");
    }

    static void check(String n, boolean ok, String d) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + n + "  -> " + d); }
    }

    /** Runs a raw event stream through the SSE reader and the decoder, the way AiClient does. */
    static class Result {
        final StringBuilder text = new StringBuilder();
        String stop, error;
        boolean done, thinking;
        int events;
    }

    static Result run(Kind kind, String stream) {
        final Result r = new Result();
        AiProtocol.Sse sse = new AiProtocol.Sse((name, data) -> {
            r.events++;
            Chunk c = AiProtocol.decode(kind, name, data);
            r.text.append(c.text);
            if (c.stop != null) r.stop = c.stop;
            if (c.done) r.done = true;
            if (c.thinking) r.thinking = true;
            if (c.error != null) r.error = c.error;
        });
        for (String line : stream.split("\n", -1)) sse.line(line);
        sse.finish();
        return r;
    }

    static List<Msg> msgs(String... roleContent) {
        List<Msg> l = new ArrayList<>();
        for (int i = 0; i + 1 < roleContent.length; i += 2) l.add(new Msg(roleContent[i], roleContent[i + 1]));
        return l;
    }

    public static void main(String[] a) {
        Provider claude = AiProtocol.provider("anthropic"), openai = AiProtocol.provider("openai"), gemini = AiProtocol.provider("gemini");
        Provider local = AiProtocol.provider("local"), custom = AiProtocol.provider("custom");

        // ================================================================ providers
        check("every preset has an id, a label and a kind", Arrays.stream(AiProtocol.PROVIDERS).allMatch(p -> !p.id.isEmpty() && !p.label.isEmpty() && p.kind != null), "");
        check("unknown id falls back to Claude", AiProtocol.provider("nope") == claude && AiProtocol.provider(null) == claude, "");
        check("Claude default is a current model id", claude.defaultModel.startsWith("claude-"), claude.defaultModel);
        check("a local model needs no key, Claude does", !local.keyRequired && claude.keyRequired, "");

        // ================================================================ Claude request
        Request r = AiProtocol.chat(claude, "", "  sk-ant-test  ", "claude-sonnet-5-5", "Be brief.", msgs("user", "Hi \"there\"\nline2 \u09ec \uD83D\uDE00"), 4096);
        check("claude: url", "https://api.anthropic.com/v1/messages".equals(r.url), r.url);
        check("claude: POST", "POST".equals(r.method), r.method);
        check("claude: key header trimmed", "sk-ant-test".equals(r.headers.get("x-api-key")), "" + r.headers);
        check("claude: version header", "2023-06-01".equals(r.headers.get("anthropic-version")), "" + r.headers);
        check("claude: json + sse headers", "application/json".equals(r.headers.get("Content-Type")) && "text/event-stream".equals(r.headers.get("Accept")), "" + r.headers);
        check("claude: no bearer header", !r.headers.containsKey("Authorization"), "" + r.headers);
        Object body = MiniJson.parse(r.body);
        check("claude: body model/max_tokens/stream", "claude-sonnet-5-5".equals(MiniJson.str(body, "model")) && Long.valueOf(4096).equals(MiniJson.at(body, "max_tokens")) && Boolean.TRUE.equals(MiniJson.at(body, "stream")), r.body);
        check("claude: system is top level, not a message", "Be brief.".equals(MiniJson.str(body, "system")) && MiniJson.list(body, "messages").size() == 1, r.body);
        check("claude: message text survives quotes, newlines, unicode", "Hi \"there\"\nline2 \u09ec \uD83D\uDE00".equals(MiniJson.str(body, "messages", 0, "content")) && "user".equals(MiniJson.str(body, "messages", 0, "role")), r.body);
        check("claude: custom base ending in /v1 is not doubled, trailing slash dropped", "https://proxy.example/v1/messages".equals(AiProtocol.chat(claude, "https://proxy.example/v1/", "k", "m", "", msgs("user", "x"), 10).url), "");
        check("claude: empty system is left out", !MiniJson.parse(AiProtocol.chat(claude, "", "k", "m", "", msgs("user", "x"), 10).body).toString().contains("system"), "");

        // ================================================================ OpenAI-compatible request
        r = AiProtocol.chat(openai, "", "sk-test", "gpt-5.5", "Be brief.", msgs("user", "Hi", "assistant", "Hello", "user", "More"), 4096);
        body = MiniJson.parse(r.body);
        check("openai: url", "https://api.openai.com/v1/chat/completions".equals(r.url), r.url);
        check("openai: bearer header", "Bearer sk-test".equals(r.headers.get("Authorization")), "" + r.headers);
        check("openai: system message first", "system".equals(MiniJson.str(body, "messages", 0, "role")) && "Be brief.".equals(MiniJson.str(body, "messages", 0, "content")) && MiniJson.list(body, "messages").size() == 4, r.body);
        check("openai: stream on", Boolean.TRUE.equals(MiniJson.at(body, "stream")), r.body);
        check("openai: no max_tokens or temperature (reasoning models reject them)", !r.body.contains("max_tokens") && !r.body.contains("temperature"), r.body);
        r = AiProtocol.chat(local, "", "", "llama", "", msgs("user", "x"), 100);
        check("local: default ollama address", "http://127.0.0.1:11434/v1/chat/completions".equals(r.url), r.url);
        check("local: no key means no Authorization header", !r.headers.containsKey("Authorization"), "" + r.headers);
        check("custom: typed base used", "https://api.example.com/v1/chat/completions".equals(AiProtocol.chat(custom, " https://api.example.com/v1// ", "k", "m", "", msgs("user", "x"), 10).url), "");

        // ================================================================ Gemini request
        r = AiProtocol.chat(gemini, "", "AIza-test", "models/gemini-flash-latest", "Be brief.", msgs("user", "Hi", "assistant", "Hello", "user", "More"), 4096);
        body = MiniJson.parse(r.body);
        check("gemini: url has model and sse flag, models/ prefix not doubled", "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:streamGenerateContent?alt=sse".equals(r.url), r.url);
        check("gemini: key goes in a header, not the url", "AIza-test".equals(r.headers.get("x-goog-api-key")) && !r.url.contains("AIza"), r.url + " " + r.headers);
        check("gemini: system instruction", "Be brief.".equals(MiniJson.str(body, "systemInstruction", "parts", 0, "text")), r.body);
        check("gemini: roles become user/model", "user".equals(MiniJson.str(body, "contents", 0, "role")) && "model".equals(MiniJson.str(body, "contents", 1, "role")) && "More".equals(MiniJson.str(body, "contents", 2, "parts", 0, "text")), r.body);
        check("gemini: no output cap that thinking tokens would eat", !r.body.contains("maxOutputTokens"), r.body);
        check("path encoding keeps safe characters", "gemini-2.5-pro".equals(AiProtocol.encodePath("gemini-2.5-pro")) && "tunedModels/my%20model".equals(AiProtocol.encodePath("tunedModels/my model")), AiProtocol.encodePath("tunedModels/my model"));
        check("path encoding handles non-ASCII and emoji", "%E0%A7%AC".equals(AiProtocol.encodePath("\u09ec")) && "%F0%9F%98%80".equals(AiProtocol.encodePath("\uD83D\uDE00")), AiProtocol.encodePath("\uD83D\uDE00"));
        check("path encoding stops injection of ? and #", !AiProtocol.encodePath("a?b#c").contains("?") && !AiProtocol.encodePath("a?b#c").contains("#"), "");

        // ================================================================ models request
        check("models: claude", "https://api.anthropic.com/v1/models?limit=100".equals(AiProtocol.models(claude, "", "k").url) && "k".equals(AiProtocol.models(claude, "", "k").headers.get("x-api-key")), "");
        check("models: openai", "https://api.openai.com/v1/models".equals(AiProtocol.models(openai, "", "k").url) && "GET".equals(AiProtocol.models(openai, "", "k").method), "");
        check("models: gemini", AiProtocol.models(gemini, "", "k").url.endsWith("/v1beta/models?pageSize=1000"), AiProtocol.models(gemini, "", "k").url);

        // ================================================================ Claude stream (shape from the Messages streaming docs)
        String claudeStream = "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-5-5\",\"stop_reason\":null,\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}\n\n"
                + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                + "event: ping\ndata: {\"type\": \"ping\"}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" \\\"world\\\"\\n\\u00e9\"}}\n\n"
                + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":15}}\n\n"
                + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        Result res = run(Kind.ANTHROPIC, claudeStream);
        check("claude stream: text joined and unescaped", "Hello \"world\"\n\u00e9".equals(res.text.toString()), res.text.toString());
        check("claude stream: done with stop reason", res.done && "end_turn".equals(res.stop) && res.error == null, "done=" + res.done + " stop=" + res.stop);
        check("claude stream: eight events seen", res.events == 8, "" + res.events);
        res = run(Kind.ANTHROPIC, "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Let me think\"}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"EqQB\"}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"a\\\"\"}}\n\n");
        check("claude stream: thinking is flagged and never shown as text", res.thinking && res.text.length() == 0, res.text.toString());
        res = run(Kind.ANTHROPIC, "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n");
        check("claude stream: error event carries the message", "Overloaded".equals(res.error), "" + res.error);
        res = run(Kind.ANTHROPIC, "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"no event line\"}}\n\n");
        check("claude stream: works without 'event:' lines (type is in the JSON)", "no event line".equals(res.text.toString()), res.text.toString());

        // ================================================================ OpenAI stream
        String oaStream = "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n"
                + "data:{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" world\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"id\":\"c1\",\"choices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":2}}\n\n"
                + "data: [DONE]\n\n";
        res = run(Kind.OPENAI, oaStream);
        check("openai stream: text joined (also with 'data:' and no space)", "Hello world".equals(res.text.toString()), res.text.toString());
        check("openai stream: stop reason and [DONE]", res.done && "stop".equals(res.stop) && res.error == null, "done=" + res.done + " stop=" + res.stop);
        res = run(Kind.OPENAI, "data: {\"choices\":[{\"delta\":{\"content\":null}}]}\n\ndata: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hmm\"}}]}\n\n");
        check("openai stream: null content and reasoning do not crash or leak", res.text.length() == 0 && res.thinking && res.error == null, res.text.toString());
        res = run(Kind.OPENAI, "data: {\"choices\":[{\"delta\":{\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]}}]}\n\n");
        check("openai stream: content given as an array of parts", "ab".equals(res.text.toString()), res.text.toString());
        res = run(Kind.OPENAI, "data: {\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit\"}}\n\n");
        check("openai stream: error chunk", "Rate limit reached".equals(res.error), "" + res.error);
        res = run(Kind.OPENAI, "data: not json at all\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n");
        check("openai stream: garbage line skipped, stream continues", "ok".equals(res.text.toString()) && res.error == null, res.text.toString());
        res = run(Kind.OPENAI, "data: {\"choices\":[{\"delta\":{\"content\":\"last\"}}]}");
        check("sse: final event without a blank line still delivered", "last".equals(res.text.toString()), res.text.toString());
        res = run(Kind.OPENAI, ": keep-alive\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n: another\n\n");
        check("sse: comment lines ignored", "x".equals(res.text.toString()) && res.events == 1, "events=" + res.events);
        res = run(Kind.OPENAI, "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\ndata: {\"choices\":[{\"delta\":{\"content\":\"b\"}}]}\n\n");
        check("sse: two data lines in one event are joined (and then fail to parse, not crash)", res.events == 1 && res.error == null, "events=" + res.events);

        // ================================================================ Gemini stream
        String gemStream = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hel\"}],\"role\":\"model\"},\"index\":0}],\"usageMetadata\":{\"promptTokenCount\":5}}\n\n"
                + "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"lo\"}],\"role\":\"model\"},\"finishReason\":\"STOP\",\"index\":0}]}\r\n\r\n";
        res = run(Kind.GEMINI, gemStream.replace("\r\n", "\n"));
        check("gemini stream: text joined", "Hello".equals(res.text.toString()), res.text.toString());
        check("gemini stream: finishReason ends it", res.done && "STOP".equals(res.stop), "done=" + res.done);
        res = run(Kind.GEMINI, "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"plan\",\"thought\":true},{\"text\":\"answer\"}]}}]}\n\n");
        check("gemini stream: thought parts are not shown", res.thinking && "answer".equals(res.text.toString()), res.text.toString());
        res = run(Kind.GEMINI, "data: {\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}\n\n");
        check("gemini stream: blocked prompt becomes an error", res.error != null && res.error.contains("SAFETY"), "" + res.error);
        res = run(Kind.GEMINI, "data: {\"error\":{\"code\":429,\"message\":\"Quota exceeded\",\"status\":\"RESOURCE_EXHAUSTED\"}}\n\n");
        check("gemini stream: error object", "Quota exceeded".equals(res.error), "" + res.error);
        res = run(Kind.GEMINI, "data: {\"candidates\":[{\"finishReason\":\"FINISH_REASON_UNSPECIFIED\"}]}\n\n");
        check("gemini stream: UNSPECIFIED is not an end", !res.done, "");

        // ================================================================ notes and HTTP errors
        check("stop note: length cut-off explained", AiProtocol.stopNote("max_tokens") != null && AiProtocol.stopNote("MAX_TOKENS") != null && AiProtocol.stopNote("length") != null, "");
        check("stop note: safety explained", AiProtocol.stopNote("SAFETY") != null && AiProtocol.stopNote("refusal") != null, "");
        check("stop note: normal endings are silent", AiProtocol.stopNote("end_turn") == null && AiProtocol.stopNote("stop") == null && AiProtocol.stopNote("STOP") == null && AiProtocol.stopNote(null) == null, "");
        String e = AiProtocol.httpError(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");
        check("http 401 (Claude shape): message and what to do", e.startsWith("HTTP 401: invalid x-api-key") && e.contains("Settings"), e);
        e = AiProtocol.httpError(404, "{\"error\":{\"message\":\"The model `gpt-9` does not exist\",\"type\":\"invalid_request_error\",\"code\":\"model_not_found\"}}");
        check("http 404 (OpenAI shape): points at Fetch models", e.contains("gpt-9") && e.contains("Fetch models"), e);
        e = AiProtocol.httpError(429, "{\"error\":{\"code\":429,\"message\":\"Quota exceeded\",\"status\":\"RESOURCE_EXHAUSTED\"}}");
        check("http 429 (Gemini shape)", e.contains("Quota exceeded") && e.contains("quota"), e);
        e = AiProtocol.httpError(502, "<html><body><h1>Bad gateway</h1></body></html>");
        check("http 502 HTML page is not dumped on the person", !e.contains("<html>") && e.startsWith("HTTP 502") && e.contains("Try again"), e);
        e = AiProtocol.httpError(400, "plain text problem");
        check("http 400 plain text kept", e.equals("HTTP 400: plain text problem"), e);
        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 1000; i++) longBody.append('x');
        check("long bodies are cut", AiProtocol.httpError(400, longBody.toString()).length() < 300, "" + AiProtocol.httpError(400, longBody.toString()).length());
        check("null body does not crash", AiProtocol.httpError(500, null).startsWith("HTTP 500"), "");
        check("error object as plain string", "boom".equals(AiProtocol.errorText(MiniJson.parse("{\"error\":\"boom\"}"))), "");

        // ================================================================ models response
        List<String> ms = AiProtocol.parseModels(Kind.ANTHROPIC, "{\"data\":[{\"type\":\"model\",\"id\":\"claude-opus-5-5\",\"display_name\":\"Claude Opus 5.5\"},{\"type\":\"model\",\"id\":\"claude-sonnet-5-5\"}],\"has_more\":false}");
        check("models: claude order kept", ms.equals(Arrays.asList("claude-opus-5-5", "claude-sonnet-5-5")), "" + ms);
        ms = AiProtocol.parseModels(Kind.OPENAI, "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-5\"},{\"id\":\"text-embedding-3-large\"},{\"id\":\"whisper-1\"},{\"id\":\"gpt-5.5\"},{\"id\":\"dall-e-3\"},{\"id\":\"tts-1\"},{\"id\":\"gpt-image-1\"},{\"id\":\"o4-mini\"}]}");
        check("models: openai drops non-chat models, newest-looking first", ms.equals(Arrays.asList("o4-mini", "gpt-5.5", "gpt-5")), "" + ms);
        ms = AiProtocol.parseModels(Kind.OPENAI, "{\"data\":[{\"id\":\"llama3.2:latest\"},{\"id\":\"qwen2.5-coder\"}]}");
        check("models: local ids are kept", ms.size() == 2 && ms.contains("llama3.2:latest"), "" + ms);
        ms = AiProtocol.parseModels(Kind.GEMINI, "{\"models\":[{\"name\":\"models/gemini-3.6-flash\",\"supportedGenerationMethods\":[\"generateContent\",\"countTokens\"]},{\"name\":\"models/text-embedding-004\",\"supportedGenerationMethods\":[\"embedContent\"]},{\"name\":\"models/gemini-pro-latest\"}]}");
        check("models: gemini strips prefix and keeps only chat models", ms.equals(Arrays.asList("gemini-3.6-flash", "gemini-pro-latest")), "" + ms);
        check("models: garbage gives an empty list", AiProtocol.parseModels(Kind.OPENAI, "<html>").isEmpty() && AiProtocol.parseModels(Kind.GEMINI, "").isEmpty() && AiProtocol.parseModels(Kind.ANTHROPIC, "{}").isEmpty(), "");

        // ================================================================ conversation
        List<Msg> n = AiProtocol.normalize(msgs("assistant", "stray", "user", "a", "user", "b", "assistant", "c", "assistant", "", "assistant", "d", "user", "  "));
        check("normalize: drops leading assistant, empty turns; merges same-role neighbours", n.size() == 2 && n.get(0).content.equals("a\n\nb") && n.get(1).content.equals("c\n\nd") && n.get(0).role.equals("user"), n.size() + " " + (n.isEmpty() ? "" : n.get(0).content));
        List<Msg> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) { many.add(new Msg("user", "q" + i)); many.add(new Msg("assistant", "a" + i)); }
        List<Msg> t = AiProtocol.trimHistory(many, 6, 1000000);
        check("trim: keeps the newest, starts with a user turn, alternates", t.size() == 6 && t.get(0).role.equals("user") && t.get(5).content.equals("a19") && t.get(0).content.equals("q17"), t.size() + " " + t.get(0).content);
        List<Msg> big = msgs("user", "x", "assistant", "yy", "user", "zzzz");
        t = AiProtocol.trimHistory(big, 10, 5);
        check("trim: char budget keeps the newest message even if it alone is too big", t.size() == 1 && t.get(0).content.equals("zzzz"), t.size() + "");
        check("trim: nothing in, nothing out", AiProtocol.trimHistory(new ArrayList<Msg>(), 5, 5).isEmpty(), "");

        // ================================================================ prompt composition
        AiProtocol.Context c = new AiProtocol.Context();
        c.task = "  Fix the crash  ";
        c.filePath = "app/src/Main.java";
        c.fileLang = "java";
        c.fileText = "class Main {\n  void f() {}\n}\n";
        c.selection = "void f() {}";
        c.selStartLine = 2;
        c.selEndLine = 2;
        c.projectNote = "Android app, Java";
        c.problems.add("Main.java:2:3 error: cannot find symbol");
        c.tree.add("app/build.gradle");
        c.tree.add("app/src/Main.java");
        String p = AiProtocol.compose(c);
        int iProj = p.indexOf("<project>"), iSel = p.indexOf("<selection"), iFile = p.indexOf("<file "), iProb = p.indexOf("<problems>"), iTree = p.indexOf("<project_files>"), iTask = p.indexOf("<task>");
        check("compose: sections in a stable order", iProj >= 0 && iProj < iSel && iSel < iFile && iFile < iProb && iProb < iTree && iTree < iTask, p);
        check("compose: selection carries file and line range", p.contains("<selection file=\"app/src/Main.java\" lines=\"2-2\">"), p);
        check("compose: file tag has path and language", p.contains("<file path=\"app/src/Main.java\" language=\"java\">"), p);
        check("compose: task is last and trimmed", p.endsWith("<task>\nFix the crash\n</task>"), p);
        AiProtocol.Context only = new AiProtocol.Context();
        only.task = "Hello";
        check("compose: with no context it is just the task", "<task>\nHello\n</task>".equals(AiProtocol.compose(only)), AiProtocol.compose(only));
        c.filePath = "a\"<b>.java";
        check("compose: quotes and angle brackets in names cannot break out of a tag", !AiProtocol.compose(c).contains("a\"<b>") && AiProtocol.compose(c).contains("a&quot;&lt;b>.java"), AiProtocol.compose(c));
        AiProtocol.Context secret = new AiProtocol.Context();
        secret.task = "x";
        secret.fileText = "String k = \"AKIAABCDEFGHIJKLMNOP\";";
        secret.problems.add("token ghp_abcdefghijklmnopqrstuvwxyz0123456789 leaked");
        String sp = AiProtocol.compose(secret);
        check("compose: secrets in file and problems are masked", !sp.contains("AKIAABCDEFGHIJKLMNOP") && !sp.contains("ghp_abcdef") && sp.contains("[secret removed]"), sp);
        AiProtocol.Context many2 = new AiProtocol.Context();
        many2.task = "x";
        for (int i = 0; i < 400; i++) many2.tree.add("f" + i);
        for (int i = 0; i < 100; i++) many2.problems.add("p" + i);
        String mp = AiProtocol.compose(many2);
        check("compose: long lists are capped and say so", mp.contains("f249\n") && !mp.contains("f250\n") && mp.contains("150 more not shown") && mp.contains("70 more not shown"), mp.length() + "");

        // ---- big files: a window around the cursor
        StringBuilder bigFile = new StringBuilder();
        for (int i = 1; i <= 5000; i++) bigFile.append("line number ").append(i).append(" of the big file\n");
        AiProtocol.Context bc = new AiProtocol.Context();
        bc.task = "x";
        bc.filePath = "Big.java";
        bc.fileLang = "java";
        bc.fileText = bigFile.toString();
        bc.cursorLine = 2500;
        String bp = AiProtocol.compose(bc);
        check("window: big file is truncated and says which lines", bp.contains("truncated=\"true\"") && bp.contains(" of 5001\""), bp.substring(0, Math.min(200, bp.length())));
        check("window: the cursor line is inside the window", bp.contains("line number 2500 of the big file"), "");
        check("window: far away lines are not", !bp.contains("line number 10 of") && !bp.contains("line number 4900 of"), "");
        check("window: stays near the budget", bp.length() < AiProtocol.MAX_FILE_CHARS + 1000, "" + bp.length());
        int[] w = AiProtocol.window(bigFile.toString(), 1, 500);
        check("window: at the top it grows downward only", w[0] == 1 && w[1] > 5, Arrays.toString(w));
        w = AiProtocol.window(bigFile.toString(), 999999, 500);
        check("window: line past the end is clamped to the last line", w[1] == 5001 && w[0] < 5001, Arrays.toString(w));
        w = AiProtocol.window("one very long line that is longer than the budget allows", 1, 5);
        check("window: a single oversize line is still returned", w[0] == 1 && w[1] == 1 && w[2] == 1, Arrays.toString(w));
        AiProtocol.Context withSel = new AiProtocol.Context();
        withSel.task = "x";
        withSel.filePath = "Big.java";
        withSel.fileLang = "java";
        withSel.fileText = bigFile.toString();
        withSel.selection = "line number 3000 of the big file";
        withSel.selStartLine = 3000;
        withSel.selEndLine = 3000;
        String ws = AiProtocol.compose(withSel);
        check("window: with a selection only a smaller window of a big file is sent, centred on the selection", ws.length() < AiProtocol.MAX_FILE_WITH_SELECTION + 1500 && ws.contains("truncated=\"true\"") && ws.indexOf("line number 2999 of") > 0 && !ws.contains("line number 100 of"), "" + ws.length());
        AiProtocol.Context smallSel = new AiProtocol.Context();
        smallSel.task = "x";
        smallSel.fileText = "a\nb\nc\n";
        smallSel.selection = "b";
        check("window: a small file is sent whole even with a selection", AiProtocol.compose(smallSel).contains("<file ") && !AiProtocol.compose(smallSel).contains("truncated"), "");
        AiProtocol.Context hugeSel = new AiProtocol.Context();
        hugeSel.task = "x";
        hugeSel.selection = new String(new char[AiProtocol.MAX_SELECTION_CHARS + 500]).replace('\0', 'a');
        check("compose: huge selection is clipped with a note", AiProtocol.compose(hugeSel).contains("500 more characters not sent"), "");

        // ================================================================ secrets
        String key = "sk-ant-api03-" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6";
        check("redact: Claude key", !AiProtocol.redact("key=" + key).contains("sk-ant-api03"), AiProtocol.redact("key=" + key));
        check("redact: OpenAI key", AiProtocol.redact("sk-proj-" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8").equals("[secret removed]"), "");
        check("redact: GitHub tokens", AiProtocol.redact("ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8").equals("[secret removed]") && AiProtocol.redact("github_pat_" + "11ABCDEFG0123456789_" + "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123456789ab").equals("[secret removed]"), "");
        check("redact: AWS and Google keys", AiProtocol.redact("AKIAABCDEFGHIJKLMNOP").equals("[secret removed]") && AiProtocol.redact("AIza" + "SyA1B2C3D4E5F6G7H8I9J0K1L2M3N4O5P6Q").equals("[secret removed]"), "");
        check("redact: private key block", AiProtocol.redact("a\n-----BEGIN RSA PRIVATE KEY-----\nMIIabc\ndef\n-----END RSA PRIVATE KEY-----\nb").equals("a\n[secret removed]\nb"), AiProtocol.redact("a\n-----BEGIN RSA PRIVATE KEY-----\nMIIabc\ndef\n-----END RSA PRIVATE KEY-----\nb"));
        check("redact: quoted value assigned to a secret-looking name", AiProtocol.redact("val apiKey = \"abcdefghijklmnop\"").equals("val apiKey = \"[secret removed]\""), AiProtocol.redact("val apiKey = \"abcdefghijklmnop\""));
        check("redact: json style", AiProtocol.redact("\"password\": \"hunter2hunter2hunter2\"").contains("[secret removed]"), AiProtocol.redact("\"password\": \"hunter2hunter2hunter2\""));
        String normal = "String token = nextToken();\nint password = 5;\nname = \"short\"\nsecret = \"tiny\"\nURL u = new URL(\"https://example.com/sk-learn\");";
        check("redact: ordinary code is untouched", AiProtocol.redact(normal).equals(normal), AiProtocol.redact(normal));
        check("redact: null and empty are safe", AiProtocol.redact(null) == null && AiProtocol.redact("").isEmpty(), "");
        check("secret files: credentials are never attached", AiProtocol.isSecretFile("app/release.jks") && AiProtocol.isSecretFile(".env") && AiProtocol.isSecretFile("cfg/.env.local") && AiProtocol.isSecretFile("local.properties")
                && AiProtocol.isSecretFile("secrets.json") && AiProtocol.isSecretFile("keystore.properties") && AiProtocol.isSecretFile("a/b/id_rsa") && AiProtocol.isSecretFile("key.pem") && AiProtocol.isSecretFile("credentials.xml"), "");
        check("secret files: code about secrets is fine", !AiProtocol.isSecretFile("SecretManager.java") && !AiProtocol.isSecretFile("Credentials.kt") && !AiProtocol.isSecretFile("Main.java") && !AiProtocol.isSecretFile("build.gradle") && !AiProtocol.isSecretFile("README.md") && !AiProtocol.isSecretFile(null), "");
        check("token estimate", AiProtocol.estimateTokens(0) == 0 && AiProtocol.estimateTokens(4) == 1 && AiProtocol.estimateTokens(5) == 2, "");

        // ================================================================ replies: prose and code blocks
        List<Block> b = AiProtocol.blocks("Here is the fix:\n\n```java\nint a = 1;\n\nint b = 2;\n```\n\nThat is it.");
        check("blocks: prose, code, prose", b.size() == 3 && !b.get(0).code && b.get(1).code && !b.get(2).code, "" + b.size());
        check("blocks: code keeps blank lines and language", b.get(1).lang.equals("java") && b.get(1).text.equals("int a = 1;\n\nint b = 2;") && !b.get(1).open, "[" + b.get(1).text + "]");
        check("blocks: prose trimmed", b.get(0).text.equals("Here is the fix:") && b.get(2).text.equals("That is it."), "[" + b.get(0).text + "][" + b.get(2).text + "]");
        b = AiProtocol.blocks("Start\n```py\nprint(1)");
        check("blocks: a fence still streaming is open", b.size() == 2 && b.get(1).code && b.get(1).open && b.get(1).text.equals("print(1)"), "" + b.size());
        b = AiProtocol.blocks("~~~\nx\n~~~");
        check("blocks: tilde fences", b.size() == 1 && b.get(0).code && b.get(0).text.equals("x") && b.get(0).lang.isEmpty(), "");
        b = AiProtocol.blocks("````md\n```js\ninner\n```\n````\nafter");
        check("blocks: a longer outer fence holds a shorter inner one", b.size() == 2 && b.get(0).code && b.get(0).text.equals("```js\ninner\n```") && b.get(0).lang.equals("md"), "" + b.get(0).text);
        b = AiProtocol.blocks("```kotlin title=\"x.kt\"\nval a = 1\n```");
        check("blocks: info string after the language is ignored", b.get(0).lang.equals("kotlin"), b.get(0).lang);
        b = AiProtocol.blocks("   ```\n   indented\n   ```");
        check("blocks: fences indented up to three spaces", b.size() == 1 && b.get(0).code, "" + b.size());
        b = AiProtocol.blocks("```\ncode with ``` inside a line\n```");
        check("blocks: backticks inside a code line do not close it", b.size() == 1 && b.get(0).text.equals("code with ``` inside a line"), b.get(0).text);
        b = AiProtocol.blocks("no code here\nat all");
        check("blocks: plain text is one block", b.size() == 1 && !b.get(0).code && b.get(0).text.equals("no code here\nat all"), "");
        check("blocks: empty gives nothing", AiProtocol.blocks("").isEmpty() && AiProtocol.blocks("\n\n").isEmpty(), "");
        b = AiProtocol.blocks("```\none\n```\n```\ntwo\n```");
        check("blocks: back to back code blocks", b.size() == 2 && b.get(0).text.equals("one") && b.get(1).text.equals("two"), "" + b.size());
        check("codeOnly joins code and drops prose", AiProtocol.codeOnly("a\n```\none\n```\nb\n```\ntwo\n```").equals("one\n\ntwo"), AiProtocol.codeOnly("a\n```\none\n```\nb\n```\ntwo\n```"));

        // ================================================================ quick actions and inline Markdown
        check("quick actions: nine, each with a label, an instruction and a 0/1 flag (and \"p\" for whole-project ones)", AiProtocol.QUICK.length == 9 && Arrays.stream(AiProtocol.QUICK).allMatch(q -> (q.length == 3 || (q.length == 4 && q[3].equals("p"))) && !q[0].isEmpty() && q[1].length() > 40 && (q[2].equals("0") || q[2].equals("1"))), "");
        check("quick actions: labels are sentence case, unique", Arrays.stream(AiProtocol.QUICK).map(q -> q[0]).distinct().count() == 9 && Arrays.stream(AiProtocol.QUICK).allMatch(q -> !q[0].equals(q[0].toUpperCase(Locale.ROOT)) || q[0].length() < 2), "");
        check("quick actions: only Fix and Review project pull in the problems list", Arrays.stream(AiProtocol.QUICK).filter(q -> q[2].equals("1")).map(q -> q[0]).collect(java.util.stream.Collectors.toList()).equals(Arrays.asList("Fix", "Review project")), "");
        check("quick actions: the two whole-project ones are the last two (the editor menu uses index 8)", AiProtocol.QUICK[7].length == 4 && AiProtocol.QUICK[8].length == 4 && AiProtocol.QUICK[8][0].equals("Review project") && AiProtocol.QUICK[6].length == 3, "");

        // ================================================================ whole-project code
        AiProtocol.Context pc = new AiProtocol.Context();
        pc.task = "review";
        check("project code: a normal file is added", AiProtocol.addCode(pc, "app/src/A.kt", "class A") && pc.code.size() == 1, "" + pc.code.size());
        AiProtocol.addCode(pc, "app/local.properties", "sdk.dir=/x");
        AiProtocol.addCode(pc, "package-lock.json", "{}");
        AiProtocol.addCode(pc, "keys/release.jks", "binary");
        AiProtocol.addCode(pc, "app/empty.kt", "   \n");
        check("project code: credential, lock and empty files are left out", pc.code.size() == 1, "" + pc.code.size());
        pc.codeTotal = 3;
        String pp = AiProtocol.compose(pc);
        check("project code: compose writes a project_code block with a partial count", pp.contains("<project_code files=\"1 of 3\" partial=\"true\">") && pp.contains("<file path=\"app/src/A.kt\">\nclass A\n</file>") && pp.indexOf("</project_code>") < pp.indexOf("<task>"), pp);
        AiProtocol.Context bigCtx = new AiProtocol.Context();
        int added = 0;
        StringBuilder blob = new StringBuilder();
        for (int i = 0; i < 5000; i++) blob.append("line ").append(i).append('\n');
        while (AiProtocol.addCode(bigCtx, "f" + added + ".kt", blob.toString())) added++;
        check("project code: stops at the budget, one file never over its cap", bigCtx.codeChars < AiProtocol.MAX_PROJECT_CHARS + 200 && added >= 5 && bigCtx.code.get(0)[1].length() < AiProtocol.MAX_PROJECT_FILE + 100, bigCtx.codeChars + " in " + added);
        check("project code: a secret in a project file is masked in the request", secretMasked(), "");
        check("project code: build files rank before source, source before xml before the rest", AiProtocol.codeRank("app/build.gradle") < AiProtocol.codeRank("app/A.kt") && AiProtocol.codeRank("app/A.kt") < AiProtocol.codeRank("app/res/a.xml") && AiProtocol.codeRank("app/res/a.xml") < AiProtocol.codeRank("notes.txt"), "");

        // ================================================================ editing files
        List<AiProtocol.Block> fb = AiProtocol.blocks("Fixed it.\nFile: app/src/A.kt\n```kotlin\nclass A\n```\n**File: `app/res/layout/x.xml`**\n```xml\n<a/>\n```\nDone.");
        check("file blocks: a File line marks the next code block and leaves the prose", fb.size() == 4 && !fb.get(0).code && fb.get(0).text.equals("Fixed it.") && fb.get(1).path.equals("app/src/A.kt") && fb.get(1).lang.equals("kotlin") && fb.get(2).path.equals("app/res/layout/x.xml") && fb.get(3).text.equals("Done."), fb.size() + " " + (fb.size() > 1 ? fb.get(1).path : ""));
        fb = AiProtocol.blocks("File: ../../etc/passwd\n```\nx\n```");
        check("file blocks: an unsafe path is not a file block, the line stays as prose", fb.size() == 2 && fb.get(1).path == null && fb.get(0).text.contains("passwd"), fb.size() + "");
        fb = AiProtocol.blocks("Use this:\n```java\nint a;\n```");
        check("file blocks: a code block with no File line has no path", fb.size() == 2 && fb.get(1).path == null, "");
        fb = AiProtocol.blocks("File: a/B.java\n```java\nclass B {");
        check("file blocks: a block still streaming keeps its path and stays open", fb.size() == 1 && fb.get(0).open && "a/B.java".equals(fb.get(0).path), fb.size() + "");
        check("path: normal relative paths pass, ./ is dropped", "app/src/Main.kt".equals(AiProtocol.cleanPath("app/src/Main.kt")) && "a/b.txt".equals(AiProtocol.cleanPath("./a/b.txt")) && "a/b.txt".equals(AiProtocol.cleanPath("a\\b.txt")), "");
        String[] badPaths = {"/etc/passwd", "../x.kt", "a/../../x", "a//b", ".git/config", "app/build/out.kt", "node_modules/x/y.js", "C:/x.kt", "a/b/", "", "app/logo.png", "app/release.jks", ".env", "local.properties", "app/.gradle/x.kt"};
        StringBuilder bad = new StringBuilder();
        for (String badPath : badPaths) if (AiProtocol.cleanPath(badPath) != null) bad.append(badPath).append(' ');
        check("path: absolute, .., build output, .git, binaries and credential files are refused", bad.length() == 0, bad.toString());
        check("path: app/build.gradle is fine (only a folder named build is blocked)", "app/build.gradle".equals(AiProtocol.cleanPath("app/build.gradle")), "");
        String oldFile = "line\n".repeat(60);
        check("unsafe: a complete file is fine", AiProtocol.unsafeReason(oldFile, oldFile + "more\n") == null && AiProtocol.unsafeReason(null, "class A {}") == null, "");
        check("unsafe: a ... placeholder line is caught", AiProtocol.unsafeReason("a\nb", "fun x() {\n    // ...\n}") != null && AiProtocol.unsafeReason("a\nb", "// rest of the file unchanged\nx") != null, "");
        check("unsafe: a placeholder the file already had is not blamed", AiProtocol.unsafeReason("// ...\nx", "// ...\ny") == null, "");
        check("unsafe: String... varargs is not a placeholder", AiProtocol.unsafeReason(null, "void f(String... a) {}") == null, "");
        check("unsafe: a masked secret would overwrite the real key", AiProtocol.unsafeReason("key = \"abc\"", "key = \"[secret removed]\"") != null, "");
        check("unsafe: text cut before sending is refused", AiProtocol.unsafeReason("a", "x\n[... 40 more characters not sent ...]") != null, "");
        check("unsafe: a 60-line file shrinking to 10 lines is caught, 30 lines is not", AiProtocol.unsafeReason(oldFile, "x\n".repeat(10)) != null && AiProtocol.unsafeReason(oldFile, "x\n".repeat(30)) == null, "");
        check("edit rules: say how to mark a whole file, and mention direct editing only when it is on", AiProtocol.editRules(false).contains("File: path") && !AiProtocol.editRules(false).contains("direct editing") && AiProtocol.editRules(true).contains("direct editing"), "");
        check("edit rules: the default prompt knows about project_code", AiProtocol.DEFAULT_SYSTEM_PROMPT.contains("project_code"), "");
        check("quick actions: every one asks for applicable output or a short answer", Arrays.stream(AiProtocol.QUICK).allMatch(q -> q[1].contains("code") || q[1].contains("Review") || q[1].contains("Explain")), "");
        List<AiProtocol.Seg> sg = AiProtocol.inline("a **bold** and `code` here");
        check("inline: plain, bold, plain, code, plain", sg.size() == 5 && sg.get(1).bold && sg.get(1).text.equals("bold") && sg.get(3).code && sg.get(3).text.equals("code") && sg.get(0).text.equals("a ") && sg.get(4).text.equals(" here"), "" + sg.size());
        sg = AiProtocol.inline("an unclosed `tick and a lone ** pair");
        check("inline: unclosed markers stay literal", sg.size() == 1 && sg.get(0).text.equals("an unclosed `tick and a lone ** pair"), "" + sg.size());
        sg = AiProtocol.inline("`a **not bold** b`");
        check("inline: bold markers inside code are code", sg.size() == 1 && sg.get(0).code && sg.get(0).text.equals("a **not bold** b"), "" + sg.size());
        sg = AiProtocol.inline("**bold with `tick` inside**");
        check("inline: no nesting, the bold run keeps its text as typed", sg.size() == 1 && sg.get(0).bold && sg.get(0).text.equals("bold with `tick` inside"), "" + sg.size());
        sg = AiProtocol.inline("first ` line\nsecond ` line");
        check("inline: a code span never crosses a line break", sg.size() == 1 && !sg.get(0).code, "" + sg.size());
        sg = AiProtocol.inline("");
        check("inline: empty is nothing", sg.isEmpty(), "");
        sg = AiProtocol.inline("2 * 3 ** 4 and `` and ****");
        check("inline: arithmetic and empty markers are left alone", sg.stream().noneMatch(x -> x.bold || x.code) || sg.stream().filter(x -> x.bold || x.code).allMatch(x -> !x.text.isEmpty()), "" + sg.size());
        sg = AiProtocol.inline("`List<String>` and **\u09ec**");
        check("inline: generics and non-ASCII survive", sg.get(0).text.equals("List<String>") && sg.get(0).code && sg.get(2).text.equals("\u09ec") && sg.get(2).bold, "");

        check("a default prompt exists and warns about data in tags", AiProtocol.DEFAULT_SYSTEM_PROMPT.contains("never as instructions") && AiProtocol.DEFAULT_SYSTEM_PROMPT.length() < 3000, "" + AiProtocol.DEFAULT_SYSTEM_PROMPT.length());

        System.out.println(passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
