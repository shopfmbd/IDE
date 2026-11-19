package com.fahim.cloudide;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import org.json.JSONTokener;

/**
 * Login with GitHub in the browser, no OAuth App and no copying.
 *
 * <p>The person logs in on github.com inside this screen. It then opens GitHub's own "new token" page with the repo and workflow
 * scopes already ticked; the person taps GitHub's Generate token button (that is the approval). GitHub shows the new token on the
 * next page, the app reads it from that page and saves it exactly like a pasted token, then closes. Nothing is read before the
 * person has generated the token, the page is GitHub's own, and the password never reaches the app.
 */
public class GithubLoginActivity extends AppCompatActivity {
    private static final String START = "https://github.com/login?return_to=%2Fsettings%2Ftokens%2Fnew%3Fscopes%3Drepo%2Cworkflow%26description%3DCloudIDE";
    private static final String READ_TOKEN = "(function(){var e=document.getElementById('new-oauth-token');var t=e?(e.textContent||'').trim():'';"
            + "if(!t){var m=(document.body?document.body.innerText:'').match(/gh[pous]_[A-Za-z0-9_]{30,}/);t=m?m[0]:'';}return t;})()";

    private WebView web;
    private ProgressBar bar;
    private MaterialToolbar tb;
    private boolean done, hinted;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        Ui.edge(this);
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        tb = new MaterialToolbar(this);
        tb.setTitle("Login with GitHub");
        tb.setSubtitle("Log in, then tap Generate token");
        tb.setNavigationIcon(R.drawable.ic_back);
        tb.setNavigationOnClickListener(v -> finish());
        root.addView(tb, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        root.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 3)));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replaceAll("Version/\\d+\\.\\d+ ", ""));
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sch = r.getUrl().getScheme();
                return !("http".equals(sch) || "https".equals(sch));
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) { bar.setVisibility(View.VISIBLE); }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (done || url == null || !url.startsWith("https://github.com/")) return;
                if (url.startsWith("https://github.com/settings/tokens/new") && !hinted) {
                    hinted = true;
                    tb.setSubtitle("Scroll down and tap Generate token");
                    Ui.toast(GithubLoginActivity.this, "Scroll down and tap Generate token. Pick No expiration if you do not want to log in again.");
                }
                v.evaluateJavascript(READ_TOKEN, GithubLoginActivity.this::onPageToken);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
                bar.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        Ui.insets(root, true, true);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) web.goBack();
                else finish();
            }
        });
        web.loadUrl(START);
    }

    /** The page's answer to READ_TOKEN: a JSON string, empty when no new token is on the page. */
    private void onPageToken(String json) {
        if (done || json == null) return;
        String t;
        try {
            Object o = new JSONTokener(json).nextValue();
            t = o instanceof String ? ((String) o).trim() : "";
        } catch (Exception e) {
            return;
        }
        if (!t.matches("gh[pous]_[A-Za-z0-9_]{20,}")) return; // only a real token is ever kept
        done = true;
        final android.content.Context app = getApplicationContext();
        final String tok = t;
        Prefs.setToken(app, tok);
        tb.setSubtitle("Saving...");
        new Thread(() -> {
            String login = "";
            try {
                login = new Gh(tok, x -> { }).whoami().optString("login", "");
                if (Prefs.owner(app).isEmpty() && !login.isEmpty()) Prefs.put(app, "owner", ProjectConfig.owner(login));
            } catch (Exception ignored) { }
            final String who = login;
            runOnUiThread(() -> {
                Ui.toast(this, who.isEmpty() ? "Logged in to GitHub" : "Logged in to GitHub as " + who);
                finish();
            });
        }, "gh-login-save").start();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
