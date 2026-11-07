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

    static boolean customSystem(Context c) { return !Prefs.str(c, "ai_system").trim().isEmpty(); }

    static String system(Context c) { return customSystem(c) ? Prefs.str(c, "ai_system") : AiProtocol.DEFAULT_SYSTEM_PROMPT; }

    static boolean consented(Context c, AiProtocol.Provider p) { return Prefs.bool(c, "ai_ok_" + p.id, false); }

    static void setConsented(Context c, AiProtocol.Provider p, boolean ok) { Prefs.put(c, "ai_ok_" + p.id, ok); }

    /** The first provider name word, for sentences like "Add your Claude API key". */
    static String shortName(AiProtocol.Provider p) {
        int sp = p.label.indexOf(' ');
        return sp < 0 ? p.label : p.label.substring(0, sp);
    }

    /** Null when the assistant is ready to use, otherwise one sentence saying what is missing. */
    static String missing(Context c) {
        AiProtocol.Provider p = provider(c);
        if (p.keyRequired && key(c, p).isEmpty()) return "Add your " + shortName(p) + " API key to start.";
        if (base(c, p).isEmpty()) return "Enter the server address to start.";
        if (model(c, p).isEmpty()) return "Choose a model to start. Fetch models lists what this key can use.";
        return null;
    }
}
