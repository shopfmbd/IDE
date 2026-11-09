package com.fahim.cloudide;

import android.content.Context;

/**
 * The AI assistant's settings. Provider, model, address and key are stored per provider, so switching from Claude to a
 * local model and back loses nothing. Keys are encrypted with {@link Secure} (Android Keystore) and never leave the device
 * except in the request header to the provider the person chose.
 */
final class AiConfig {
    private AiConfig() { }

    static AiProtocol.Provider provider(Context c) {
        String id = Prefs.str(c, "ai_provider");
        return AiProtocol.provider(id.isEmpty() ? "anthropic" : id);
    }

    static void setProvider(Context c, AiProtocol.Provider p) { Prefs.put(c, "ai_provider", p.id); }

    static String model(Context c, AiProtocol.Provider p) {
        String m = Prefs.str(c, "ai_model_" + p.id).trim();
        return m.isEmpty() ? p.defaultModel : m;
    }

    static void setModel(Context c, AiProtocol.Provider p, String m) { Prefs.put(c, "ai_model_" + p.id, m == null ? "" : m.trim()); }

    /** What was typed in Settings; empty means "use the preset". */
    static String baseTyped(Context c, AiProtocol.Provider p) { return Prefs.str(c, "ai_base_" + p.id).trim(); }

    static String base(Context c, AiProtocol.Provider p) { return AiProtocol.effectiveBase(p, baseTyped(c, p)); }

    static void setBase(Context c, AiProtocol.Provider p, String b) { Prefs.put(c, "ai_base_" + p.id, b == null ? "" : b.trim()); }

    static String key(Context c, AiProtocol.Provider p) { return Secure.dec(Prefs.str(c, "ai_key_" + p.id)); }

    /** Stores the key. Returns false when the Keystore was unavailable and the key could only be stored without encryption. */
    static boolean setKey(Context c, AiProtocol.Provider p, String k) {
        String t = k == null ? "" : k.trim();
        if (t.isEmpty()) {
            Prefs.put(c, "ai_key_" + p.id, "");
            return true;
        }
        String enc = Secure.enc(t);
        Prefs.put(c, "ai_key_" + p.id, enc);
        return !enc.startsWith("p:");
    }

    /** The reply length limit. Models that think before they answer count that thinking too, so the default is generous. */
    static int maxTokens(Context c) {
        int v = Prefs.sp(c).getInt("ai_max", 16000);
        return v < 256 ? 16000 : v;
    }

    /** The whole-project attachment size, in thousands of tokens. */
    static int projectK(Context c) {
        int v = Prefs.sp(c).getInt("ai_project_k", 30);
        return v < 30 ? 30 : v;
    }

    /** Pushes the saved project size into the request builder. Call at start-up and whenever the setting changes. */
    static void applyLimits(Context c) { AiProtocol.setProjectBudget(projectK(c)); }

    static boolean customSystem(Context c) { return !Prefs.str(c, "ai_system").trim().isEmpty(); }

    static String system(Context c) { return customSystem(c) ? Prefs.str(c, "ai_system") : AiProtocol.DEFAULT_SYSTEM_PROMPT; }

    static boolean consented(Context c, AiProtocol.Provider p) { return Prefs.bool(c, "ai_ok_" + p.id, false); }

    static void setConsented(Context c, AiProtocol.Provider p, boolean ok) { Prefs.put(c, "ai_ok_" + p.id, ok); }

    /** The first provider name word, for sentences like "Add your Claude API key". */
    static String shortName(AiProtocol.Provider p) {
        int sp = p.label.indexOf(' ');
        return sp < 0 ? p.label : p.label.substring(0, sp);
    }

    /** What the secret is called for this provider: Puter asks for an auth token, the others for an API key. */
    static String keyName(AiProtocol.Provider p) { return "puter".equals(p.id) ? "auth token" : "API key"; }

    /** Null when the assistant is ready to use, otherwise one sentence saying what is missing. */
    static String missing(Context c) {
        AiProtocol.Provider p = provider(c);
        if (p.keyRequired && key(c, p).isEmpty()) return "Add your " + shortName(p) + " " + keyName(p) + " to start.";
        if (base(c, p).isEmpty()) return "Enter the server address to start.";
        if (model(c, p).isEmpty()) return "Choose a model to start. Fetch models lists what this key can use.";
        return null;
    }

    // ------------------------------------------------------------ sandbox
    /** "off", "local" (a folder on the phone) or "remote" (the person's own sandbox server). */
    static String sandboxMode(Context c) {
        String m = Prefs.str(c, "sbx_mode");
        return m.equals("local") || m.equals("remote") ? m : "off";
    }

    static void setSandboxMode(Context c, String m) { Prefs.put(c, "sbx_mode", m); }

    static String sandboxUrl(Context c) { return Prefs.str(c, "sbx_url").trim(); }

    static void setSandboxUrl(Context c, String u) { Prefs.put(c, "sbx_url", u == null ? "" : u.trim()); }

    static String sandboxToken(Context c) { return Secure.dec(Prefs.str(c, "sbx_token")); }

    static void setSandboxToken(Context c, String t) { Prefs.put(c, "sbx_token", t == null || t.trim().isEmpty() ? "" : Secure.enc(t.trim())); }

    /** A random id for this install: the server keeps one workspace per id. */
    static String sandboxSession(Context c) {
        String id = Prefs.str(c, "sbx_session");
        if (id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString().replace("-", "");
            Prefs.put(c, "sbx_session", id);
        }
        return id;
    }

    /** Null when the sandbox is ready, else one sentence saying what is missing. */
    static String sandboxMissing(Context c) {
        String m = sandboxMode(c);
        if (m.equals("off")) return "The sandbox is off. Turn it on in Settings > AI sandbox.";
        if (m.equals("remote")) {
            String e = SandboxClient.checkUrl(sandboxUrl(c));
            if (e != null) return e;
            if (sandboxToken(c).isEmpty()) return "Enter the sandbox server token in Settings > AI sandbox.";
        }
        return null;
    }
}
