package com.fahim.cloudide;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Chat with Claude (and other models) through Puter.js, the browser library, running in a WebView.
 *
 * <p>The page is bundled in assets/puter_chat.html and loads https://js.puter.com/v2/. The person signs in to Puter in a popup
 * (Puter.js opens one for sign-in), so no token has to be copied. If a Puter auth token is already saved in Settings it is
 * handed to the page so sign-in can be skipped. The page is this app's own file; it has no bridge into the app, so it cannot
 * read or write project files.
 */
public class PuterWebActivity extends AppCompatActivity {
    /** A neutral https origin for the bundled page, so Puter.js sees a normal web page. */
    private static final String BASE = "https://cloudide.invalid/";

    private WebView web;
    private ProgressBar bar;
    private Dialog popup;
    private WebView popupWeb;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        Ui.edge(this);
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        MaterialToolbar tb = new MaterialToolbar(this);
        tb.setTitle("Puter chat");
        tb.setSubtitle("Puter.js");
        tb.setNavigationIcon(R.drawable.ic_back);
        tb.setNavigationOnClickListener(v -> finish());
        tb.getMenu().add(0, 1, 0, "Reload");
        tb.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 1) load();
            return true;
        });
        root.addView(tb, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        root.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 3)));

        web = new WebView(this);
        setup(web);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sch = r.getUrl().getScheme();
                return !("http".equals(sch) || "https".equals(sch));
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) { bar.setVisibility(View.VISIBLE); }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) Ui.toast(PuterWebActivity.this, "Could not load the page: " + e.getDescription());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
                bar.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }

            /** Puter.js opens its sign-in in a popup window; show it on top and keep the opener link so the result reaches the page. */
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                closePopup();
                popupWeb = new WebView(PuterWebActivity.this);
                setup(popupWeb);
                popupWeb.setWebViewClient(new WebViewClient());
                popupWeb.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public void onCloseWindow(WebView w) { closePopup(); }
                });
                CookieManager.getInstance().setAcceptThirdPartyCookies(popupWeb, true);
                popup = new Dialog(PuterWebActivity.this, android.R.style.Theme_DeviceDefault_NoActionBar);
                popup.setContentView(popupWeb, new ViewGroup.LayoutParams(-1, -1));
                popup.setOnDismissListener(d -> destroyPopup());
                popup.show();
                WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
                t.setWebView(popupWeb);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView w) { closePopup(); }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        Ui.insets(root, true, true);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (popup != null) closePopup();
                else finish();
            }
        });

        load();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setup(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // some sign-in pages refuse the default "wv" WebView agent
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replaceAll("Version/\\d+\\.\\d+ ", ""));
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);
    }

    private void load() {
        String html;
        try (InputStream in = getAssets().open("puter_chat.html")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            html = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ui.toast(this, "Could not read the Puter page: " + e.getMessage());
            return;
        }
        String token = AiConfig.key(this, AiProtocol.provider("puter")).trim();
        // JSONObject.quote gives a safe JavaScript string literal (or "" when there is no saved token)
        html = html.replace("/*TOKEN*/\"\"", JSONObject.quote(token));
        web.loadDataWithBaseURL(BASE, html, "text/html", "UTF-8", null);
    }

    private void closePopup() {
        if (popup != null) popup.dismiss();
    }

    private void destroyPopup() {
        if (popupWeb != null) {
            popupWeb.destroy();
            popupWeb = null;
        }
        popup = null;
    }

    @Override
    protected void onDestroy() {
        closePopup();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
