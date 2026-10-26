package com.perevod.app;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String ACTION_INSTALL_STATUS = "com.perevod.app.INSTALL_STATUS";
    private static final int REQ_MIC = 7;

    private WebView web;
    private boolean pageReady = false;
    private String pendingText = null;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // voice
    private SpeechRecognizer recognizer;
    private boolean voiceActive = false;      // user wants to keep recording
    private boolean voiceFinalized = true;
    private String voiceLang = "ru-RU";
    private String pendingVoiceLang = null;
    private final StringBuilder voiceText = new StringBuilder();
    private String voicePartial = "";

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
        if (ACTION_INSTALL_STATUS.equals(intent.getAction())) {
            handleInstallStatus(intent);
            return;
        }
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
    protected void onPause() {
        super.onPause();
        if (voiceActive) stopVoice();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        if (recognizer != null) recognizer.destroy();
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

    private void voiceEvent(String type, String text) {
        js("window.__voice && window.__voice(" + JSONObject.quote(type) + "," + JSONObject.quote(text == null ? "" : text) + ")");
    }

    private void updateEvent(String type, String value) {
        js("window.__update && window.__update(" + JSONObject.quote(type) + "," + JSONObject.quote(value == null ? "" : value) + ")");
    }

    /* ================= Voice input ================= */

    private String spokenSoFar() {
        String base = voiceText.toString().trim();
        String p = voicePartial.trim();
        if (p.isEmpty()) return base;
        return base.isEmpty() ? p : base + " " + p;
    }

    private void startVoice(String lang) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceEvent("error", "Распознавание речи недоступно. Установите или включите приложение Google.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceLang = lang;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        voiceLang = lang;
        voiceText.setLength(0);
        voicePartial = "";
        voiceActive = true;
        voiceFinalized = false;
        if (recognizer != null) recognizer.destroy();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new Listener());
        voiceEvent("start", "");
        listen();
    }

    private void listen() {
        if (recognizer == null) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, voiceLang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 6000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L);
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            finishVoice();
        }
    }

    private void stopVoice() {
        if (voiceFinalized) return;
        voiceActive = false;
        try {
            if (recognizer != null) recognizer.stopListening();
        } catch (Exception ignored) { }
        // If the recognizer does not deliver final results quickly, finish with what we have.
        ui.postDelayed(this::finishVoice, 1500);
    }

    private void finishVoice() {
        if (voiceFinalized) return;
        voiceFinalized = true;
        voiceActive = false;
        String text = spokenSoFar();
        voicePartial = "";
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
        }
        voiceEvent("final", text);
    }

    private class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> r = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty() && r.get(0) != null) {
                voicePartial = r.get(0);
                voiceEvent("partial", spokenSoFar());
            }
        }

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String best = (r != null && !r.isEmpty() && r.get(0) != null) ? r.get(0).trim() : voicePartial.trim();
            voicePartial = "";
            if (!best.isEmpty()) {
                if (voiceText.length() > 0) voiceText.append(' ');
                voiceText.append(best);
            }
            if (voiceActive) {
                voiceEvent("partial", spokenSoFar());
                listen();             // keep recording until the user taps the mic again
            } else {
                finishVoice();
            }
        }

        @Override
        public void onError(int error) {
            if (voiceFinalized) return;
            boolean silence = error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            if (voiceActive && silence) {
                // pause in speech — keep any partial text and continue listening
                if (!voicePartial.trim().isEmpty()) {
                    if (voiceText.length() > 0) voiceText.append(' ');
                    voiceText.append(voicePartial.trim());
                    voicePartial = "";
                }
                listen();
                return;
            }
            if (voiceActive && error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                ui.postDelayed(MainActivity.this::listen, 300);
                return;
            }
            if (!voiceActive) { finishVoice(); return; }
            String msg;
            switch (error) {
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: msg = "Нет доступа к микрофону."; break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: msg = "Нет интернета для распознавания речи."; break;
                case SpeechRecognizer.ERROR_AUDIO: msg = "Ошибка записи звука."; break;
                default: msg = "Распознавание прервалось (код " + error + ").";
            }
            String text = spokenSoFar();
            voiceFinalized = true;
            voiceActive = false;
            voiceEvent("error", msg);
            if (!text.isEmpty()) voiceEvent("final", text);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingVoiceLang != null) {
                startVoice(pendingVoiceLang);
            } else {
                voiceEvent("error", "Разрешите доступ к микрофону, чтобы диктовать текст.");
            }
            pendingVoiceLang = null;
        }
    }

    /* ================= Self-update ================= */

    private int versionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return (int) (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (Exception e) {
            return 0;
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void downloadAndInstall(String url) {
        pool.execute(() -> {
            File apk = new File(getCacheDir(), "update.apk");
            HttpURLConnection c = null;
            try {
                String current = url;
                // follow redirects manually (GitHub → objects.githubusercontent.com)
                for (int hop = 0; hop < 6; hop++) {
                    c = (HttpURLConnection) new URL(current).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(60000);
                    c.setRequestProperty("User-Agent", "Perevodchik-Updater");
                    c.setRequestProperty("Accept", "application/octet-stream");
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String loc = c.getHeaderField("Location");
                        c.disconnect();
                        if (loc == null) throw new Exception("пустое перенаправление");
                        current = new URL(new URL(current), loc).toString();
                        continue;
                    }
                    if (code != 200) throw new Exception("HTTP " + code);
                    break;
                }
                long total = c.getContentLengthLong();
                long done = 0;
                int lastPct = -1;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct) { lastPct = pct; updateEvent("progress", String.valueOf(pct)); }
                        }
                    }
                }
                ui.post(() -> installApk(apk));
            } catch (Exception e) {
                updateEvent("error", "Не удалось скачать обновление: " + e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private void installApk(File apk) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            updateEvent("permission", "");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
            return;
        }
        updateEvent("installing", "");
        pool.execute(() -> {
            try {
                PackageInstaller installer = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(getPackageName());
                int sessionId = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                    try (InputStream in = new FileInputStream(apk);
                         OutputStream out = session.openWrite("update", 0, apk.length())) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        session.fsync(out);
                    }
                    Intent status = new Intent(MainActivity.this, MainActivity.class).setAction(ACTION_INSTALL_STATUS);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent pi = PendingIntent.getActivity(MainActivity.this, 1, status, flags);
                    session.commit(pi.getIntentSender());
                }
            } catch (Exception e) {
                updateEvent("error", "Не удалось установить: " + e.getMessage());
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void handleInstallStatus(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) return;
        int status = extras.getInt(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) extras.get(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(confirm); } catch (Exception e) { updateEvent("error", e.getMessage()); }
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            updateEvent("done", "");
        } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            updateEvent("error", "Установка отменена.");
        } else {
            String msg = extras.getString(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                msg = "Подпись не совпадает с установленной версией. Удалите старую версию и установите новую вручную.";
            }
            updateEvent("error", "Установка не удалась: " + (msg == null ? status : msg));
        }
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
                if (Build.VERSION.SDK_INT < 33) {
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

        /* voice: events arrive in window.__voice(type, text): start | partial | final | error */
        @JavascriptInterface
        public void startVoice(final String lang) {
            ui.post(() -> MainActivity.this.startVoice(lang));
        }

        @JavascriptInterface
        public void stopVoice() {
            ui.post(MainActivity.this::stopVoice);
        }

        /* update: events arrive in window.__update(type, value): progress | permission | installing | done | error */
        @JavascriptInterface
        public int versionCode() {
            return MainActivity.this.versionCode();
        }

        @JavascriptInterface
        public String versionName() {
            return MainActivity.this.versionName();
        }

        @JavascriptInterface
        public String updateRepo() {
            return getString(R.string.update_repo);
        }

        @JavascriptInterface
        public void downloadAndInstall(final String url) {
            MainActivity.this.downloadAndInstall(url);
        }
    }
}
