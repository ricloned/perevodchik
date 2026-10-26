package com.perevod.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private WebView web;
    private boolean pageReady = false;
    private String pendingText = null;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("perevod", Context.MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(getResources().getColor(R.color.bg, getTheme()));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                flushPending();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        tts = new TextToSpeech(this, status -> ttsReady = status == TextToSpeech.SUCCESS);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        CharSequence t = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            t = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_PROCESS_TEXT.equals(intent.getAction())) {
            t = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        }
        if (t != null && t.length() > 0) {
            pendingText = t.toString();
            if (pageReady) flushPending();
        }
    }

    private void flushPending() {
        if (pendingText == null || web == null) return;
        String js = "window.__incoming && window.__incoming(" + JSONObject.quote(pendingText) + ")";
        pendingText = null;
        web.evaluateJavascript(js, null);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("window.__back ? window.__back() : false", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        pool.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private void js(String code) {
        ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    /** Methods callable from the page as window.Android.* */
    private class Bridge {

        /** Async HTTP without CORS limits. Result arrives in window.__native(id, ok, status, text). */
        @JavascriptInterface
        public void request(final String id, final String method, final String url,
                            final String headersJson, final String body) {
            pool.execute(() -> {
                boolean ok = false;
                int status = 0;
                String text;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(240000);
                    c.setRequestMethod(method == null || method.isEmpty() ? "GET" : method);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Perevodchik/1.0");
                    if (headersJson != null && !headersJson.isEmpty()) {
                        JSONObject h = new JSONObject(headersJson);
                        Iterator<String> keys = h.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            c.setRequestProperty(k, h.getString(k));
                        }
                    }
                    if (body != null && !body.isEmpty()) {
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        c.setDoOutput(true);
                        c.setFixedLengthStreamingMode(bytes.length);
                        try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
                    }
                    status = c.getResponseCode();
                    text = readAll(status >= 400 ? c.getErrorStream() : c.getInputStream());
                    ok = status >= 200 && status < 400;
                } catch (Exception e) {
                    text = e.getClass().getSimpleName() + ": " + e.getMessage();
                } finally {
                    if (c != null) c.disconnect();
                }
                js("window.__native(" + JSONObject.quote(id) + "," + ok + "," + status + "," + JSONObject.quote(text) + ")");
            });
        }

        @JavascriptInterface
        public void copy(final String text) {
            ui.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Перевод", text));
                if (android.os.Build.VERSION.SDK_INT < 33) {
                    Toast.makeText(MainActivity.this, "Скопировано", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public String paste() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip()) return "";
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return "";
                CharSequence t = clip.getItemAt(0).coerceToText(MainActivity.this);
                return t == null ? "" : t.toString();
            } catch (Exception e) {
                return "";
            }
        }

        /** Returns: 1 = speaking, 0 = language not installed, -1 = TTS unavailable */
        @JavascriptInterface
        public int speak(String text, String lang) {
            if (tts == null || !ttsReady) return -1;
            int r = tts.setLanguage(Locale.forLanguageTag(lang));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return 0;
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "perevod");
            return 1;
        }

        @JavascriptInterface
        public void stopSpeaking() {
            if (tts != null) tts.stop();
        }

        @JavascriptInterface
        public void share(final String text) {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "Поделиться переводом"));
            });
        }

        @JavascriptInterface
        public void toast(final String msg) {
            ui.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public String getPref(String key) {
            return prefs.getString(key, null);
        }

        @JavascriptInterface
        public void setPref(String key, String value) {
            if (value == null) prefs.edit().remove(key).apply();
            else prefs.edit().putString(key, value).apply();
        }
    }
}
