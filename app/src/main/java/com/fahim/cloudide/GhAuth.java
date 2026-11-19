package com.fahim.cloudide;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import org.json.JSONObject;

/**
 * Sign in to GitHub through the browser (OAuth device flow), so no token has to be created or pasted.
 *
 * <p>The app asks GitHub for a short code, the person opens github.com/login/device in a browser and types it there, and the
 * app polls until GitHub hands back an access token. The token is stored exactly like a pasted one (Prefs.setToken), so
 * pushing, Actions and everything else that already uses the token keeps working. The device flow needs only a client ID
 * (public, no secret) of an OAuth App that has "Enable Device Flow" ticked.
 */
final class GhAuth {
    static final String SCOPE = "repo workflow";

    /**
     * Client ID of YOUR GitHub OAuth App (public, not a secret). Paste it here once and every user of the app signs in with one tap.
     * Create it at github.com/settings/applications/new (any name, callback https://github.com), then tick "Enable Device Flow".
     * Left empty, Settings asks for the ID instead.
     */
    static final String DEFAULT_CLIENT_ID = "";

    /** The client ID in use: the one built into the app, else the one typed in Settings. */
    static String clientId(android.content.Context c) {
        return DEFAULT_CLIENT_ID.isEmpty() ? Prefs.str(c, "gh_client_id").trim() : DEFAULT_CLIENT_ID;
    }

    /** What GitHub returns when a sign-in starts. */
    static final class Code {
        String deviceCode = "", userCode = "", verifyUrl = "https://github.com/login/device";
        int interval = 5, expiresIn = 900;
    }

    interface Cancel { boolean is(); }

    /** A sign-in that failed for a reason the person can act on. */
    static final class AuthEx extends IOException {
        AuthEx(String m) { super(m); }
    }

    private static JSONObject post(String url, String form) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent", "CloudIDE");
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        try (OutputStream o = c.getOutputStream()) {
            o.write(form.getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        java.io.InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String s = in == null ? "" : new String(Io.bytes(in), "UTF-8");
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            throw new IOException("GitHub answered " + code + " with something unreadable");
        }
    }

    private static String enc(String s) throws IOException { return URLEncoder.encode(s, "UTF-8"); }

    /** Step 1: ask GitHub for a code the person types in the browser. */
    static Code start(String clientId) throws IOException {
        JSONObject j = post("https://github.com/login/device/code", "client_id=" + enc(clientId.trim()) + "&scope=" + enc(SCOPE));
        String err = j.optString("error", "");
        if (!err.isEmpty()) {
            if (err.equals("device_flow_disabled"))
                throw new AuthEx("Device Flow is off for this OAuth App. On GitHub open the app's settings and tick \"Enable Device Flow\".");
            if (err.equals("incorrect_client_credentials") || err.equals("not_found"))
                throw new AuthEx("GitHub does not know this client ID. Check it in Settings > GitHub account.");
            throw new AuthEx("GitHub refused: " + j.optString("error_description", err));
        }
        Code c = new Code();
        c.deviceCode = j.optString("device_code", "");
        c.userCode = j.optString("user_code", "");
        String u = j.optString("verification_uri", "");
        if (u.startsWith("https://github.com/")) c.verifyUrl = u; // never send the person anywhere else
        c.interval = Math.max(1, j.optInt("interval", 5));
        c.expiresIn = j.optInt("expires_in", 900);
        if (c.deviceCode.isEmpty() || c.userCode.isEmpty()) throw new AuthEx("GitHub did not return a code. Try again.");
        return c;
    }

    /** Step 2: wait until the person approves in the browser. Blocks; returns the access token. */
    static String waitForToken(String clientId, Code c, Cancel cancel) throws IOException {
        long end = System.currentTimeMillis() + c.expiresIn * 1000L;
        int wait = c.interval;
        while (System.currentTimeMillis() < end) {
            Gh.sleep(wait * 1000L);
            if (cancel != null && cancel.is()) throw new AuthEx("Cancelled");
            JSONObject j = post("https://github.com/login/oauth/access_token",
                    "client_id=" + enc(clientId.trim()) + "&device_code=" + enc(c.deviceCode)
                            + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code"));
            String tok = j.optString("access_token", "");
            if (!tok.isEmpty()) return tok;
            String err = j.optString("error", "");
            if (err.equals("authorization_pending")) continue;
            if (err.equals("slow_down")) {
                wait = Math.max(wait + 5, j.optInt("interval", wait + 5));
                continue;
            }
            if (err.equals("expired_token")) throw new AuthEx("The code expired. Start the sign-in again.");
            if (err.equals("access_denied")) throw new AuthEx("Sign-in was cancelled on GitHub.");
            throw new AuthEx("GitHub refused: " + j.optString("error_description", err.isEmpty() ? "unknown error" : err));
        }
        throw new AuthEx("The code expired. Start the sign-in again.");
    }
}
