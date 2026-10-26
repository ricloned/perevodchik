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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.util.Base64;
import androidx.core.content.FileProvider;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String ACTION_INSTALL_STATUS = "com.perevod.app.INSTALL_STATUS";
    private static final int REQ_MIC = 7;
    private static final int REQ_GALLERY = 11;
    private static final int REQ_CAMERA = 12;
    private static final int MAX_SIDE = 1600;

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

    // offline translation
    private final RemoteModelManager modelManager = RemoteModelManager.getInstance();
    private final Map<String, Translator> translators = new HashMap<>();
    private LanguageIdentifier langId;
    private volatile boolean downloading = false;

    // photo translation
    private Uri pendingPhoto = null;
    private Uri cameraUri = null;

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
                if (pendingPhoto != null) { Uri u = pendingPhoto; pendingPhoto = null; processPhoto(u); }
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
        if (Intent.ACTION_SEND.equals(intent.getAction()) && intent.getType() != null && intent.getType().startsWith("image/")) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) {
                if (pageReady) processPhoto(u); else pendingPhoto = u;
            }
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
        synchronized (translators) { for (Translator t : translators.values()) t.close(); translators.clear(); }
        if (langId != null) langId.close();
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
        if (!isOnline()) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
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

    /* ================= Photo translation (OCR on device) ================= */

    private void photoEvent(String type, String data) {
        js("window.__photo && window.__photo(" + JSONObject.quote(type) + "," + JSONObject.quote(data == null ? "" : data) + ")");
    }

    private void pickPhoto(String source) {
        try {
            if ("camera".equals(source)) {
                File dir = new File(getCacheDir(), "photos");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("нет доступа к памяти");
                File f = new File(dir, "camera.jpg");
                cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
                Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraUri);
                i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(i, REQ_CAMERA);
            } else {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(i, "Выберите фото"), REQ_GALLERY);
            }
        } catch (Exception e) {
            photoEvent("error", "Не удалось открыть " + ("camera".equals(source) ? "камеру" : "галерею") + ": " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQ_GALLERY && data != null && data.getData() != null) processPhoto(data.getData());
        else if (requestCode == REQ_CAMERA && cameraUri != null) processPhoto(cameraUri);
    }

    private Bitmap loadBitmap(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        if (max <= 0) throw new Exception("не удалось открыть картинку");
        int sample = 1;
        while (max / (sample * 2) >= MAX_SIDE) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bm;
        try (InputStream in = getContentResolver().openInputStream(uri)) { bm = BitmapFactory.decodeStream(in, null, o); }
        if (bm == null) throw new Exception("не удалось прочитать картинку");
        int rotation = 0;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            int or = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (or == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
            else if (or == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
            else if (or == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
        } catch (Exception ignored) { }
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(bm.getWidth(), bm.getHeight()));
        if (scale < 1f || rotation != 0) {
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postRotate(rotation);
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (r != bm) bm.recycle();
            bm = r;
        }
        return bm;
    }

    private static int letters(Text t) {
        int n = 0;
        String s = t.getText();
        for (int i = 0; i < s.length(); i++) if (Character.isLetterOrDigit(s.charAt(i))) n++;
        return n;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeScript sc = Character.UnicodeScript.of(c);
        return sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA
                || sc == Character.UnicodeScript.KATAKANA || sc == Character.UnicodeScript.HANGUL;
    }

    /** Joins the lines of a block into one sentence-friendly string. */
    private static String joinLines(Text.TextBlock b) {
        StringBuilder sb = new StringBuilder();
        for (Text.Line l : b.getLines()) {
            String s = l.getText().trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) {
                char last = sb.charAt(sb.length() - 1);
                if (last == '-' && sb.length() > 1 && Character.isLetter(sb.charAt(sb.length() - 2))) {
                    sb.setLength(sb.length() - 1);           // hyphenated word break
                } else if (!(isCjk(last) && isCjk(s.charAt(0)))) {
                    sb.append(' ');
                }
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /** Average color around the box edge — the "paper" behind the text. */
    private static int edgeColor(Bitmap bm, Rect r) {
        int w = bm.getWidth(), h = bm.getHeight();
        int l = Math.max(0, r.left - 3), t = Math.max(0, r.top - 3);
        int rr = Math.min(w - 1, r.right + 3), bb = Math.min(h - 1, r.bottom + 3);
        long sr = 0, sg = 0, sbl = 0, n = 0;
        int stepX = Math.max(1, (rr - l) / 40), stepY = Math.max(1, (bb - t) / 20);
        for (int x = l; x <= rr; x += stepX) {
            int c1 = bm.getPixel(x, t), c2 = bm.getPixel(x, bb);
            sr += ((c1 >> 16) & 255) + ((c2 >> 16) & 255); sg += ((c1 >> 8) & 255) + ((c2 >> 8) & 255); sbl += (c1 & 255) + (c2 & 255); n += 2;
        }
        for (int y = t; y <= bb; y += stepY) {
            int c1 = bm.getPixel(l, y), c2 = bm.getPixel(rr, y);
            sr += ((c1 >> 16) & 255) + ((c2 >> 16) & 255); sg += ((c1 >> 8) & 255) + ((c2 >> 8) & 255); sbl += (c1 & 255) + (c2 & 255); n += 2;
        }
        if (n == 0) return 0xFFFFFF;
        return (int) ((sr / n) << 16 | (sg / n) << 8 | (sbl / n));
    }

    private void processPhoto(Uri uri) {
        photoEvent("loading", "");
        pool.execute(() -> {
            Bitmap bm = null;
            try {
                bm = loadBitmap(uri);
                InputImage img = InputImage.fromBitmap(bm, 0);
                TextRecognizer[] recognizers = {
                        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
                        TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build()),
                        TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build()),
                        TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build()),
                        TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build())
                };
                Text best = null;
                int bestScore = 0;
                String lastErr = null;
                for (TextRecognizer r : recognizers) {
                    try {
                        Text t = Tasks.await(r.process(img), 60, TimeUnit.SECONDS);
                        int s = letters(t);
                        if (s > bestScore) { best = t; bestScore = s; }
                    } catch (Exception e) {
                        lastErr = errMsg(e);
                    } finally {
                        r.close();
                    }
                }
                if (best == null) {
                    if (lastErr != null && bestScore == 0 && lastErr.toLowerCase(Locale.ROOT).contains("download")) {
                        throw new Exception("Сервисы Google ещё скачивают модуль распознавания. Подождите минуту с включённым интернетом и попробуйте снова.");
                    }
                    throw new Exception("На фото не найден текст.");
                }
                JSONArray blocks = new JSONArray();
                for (Text.TextBlock b : best.getTextBlocks()) {
                    Rect r = b.getBoundingBox();
                    String txt = joinLines(b);
                    if (r == null || txt.trim().isEmpty()) continue;
                    int bg = edgeColor(bm, r);
                    int lum = (int) (0.299 * ((bg >> 16) & 255) + 0.587 * ((bg >> 8) & 255) + 0.114 * (bg & 255));
                    blocks.put(new JSONObject()
                            .put("t", txt)
                            .put("x", r.left).put("y", r.top).put("w", r.width()).put("h", r.height())
                            .put("n", Math.max(1, b.getLines().size()))
                            .put("bg", String.format("#%06X", bg & 0xFFFFFF))
                            .put("fg", lum < 140 ? "#FFFFFF" : "#1C1B22"));
                }
                ByteArrayOutputStream jpg = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.JPEG, 85, jpg);
                JSONObject out = new JSONObject()
                        .put("img", "data:image/jpeg;base64," + Base64.encodeToString(jpg.toByteArray(), Base64.NO_WRAP))
                        .put("w", bm.getWidth()).put("h", bm.getHeight())
                        .put("blocks", blocks);
                photoEvent("result", out.toString());
            } catch (Exception e) {
                photoEvent("error", errMsg(e));
            } finally {
                if (bm != null) bm.recycle();
            }
        });
    }

    /* ================= Network ================= */

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    private boolean onWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return c != null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception e) {
            return false;
        }
    }

    /* ================= Offline translation (ML Kit, on-device) ================= */

    private void offlineEvent(String type, String json) {
        js("window.__offline && window.__offline(" + JSONObject.quote(type) + "," + JSONObject.quote(json == null ? "" : json) + ")");
    }

    private static String errMsg(Exception e) {
        Throwable t = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private Set<String> downloadedModels() throws Exception {
        Set<TranslateRemoteModel> models = Tasks.await(modelManager.getDownloadedModels(TranslateRemoteModel.class), 30, TimeUnit.SECONDS);
        Set<String> out = new HashSet<>();
        for (TranslateRemoteModel m : models) out.add(m.getLanguage());
        return out;
    }

    private String downloadedJson() {
        try {
            return new JSONArray(downloadedModels()).toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private synchronized LanguageIdentifier langId() {
        if (langId == null) {
            langId = LanguageIdentification.getClient(
                    new LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.35f).build());
        }
        return langId;
    }

    private Translator translator(String from, String to) {
        String key = from + ">" + to;
        synchronized (translators) {
            Translator t = translators.get(key);
            if (t == null) {
                t = Translation.getClient(new TranslatorOptions.Builder()
                        .setSourceLanguage(from).setTargetLanguage(to).build());
                translators.put(key, t);
            }
            return t;
        }
    }

    private static String norm(String tag) {
        if (tag == null) return "und";
        tag = tag.toLowerCase(Locale.ROOT);
        if (tag.equals("iw")) return "he";
        if (tag.equals("nb") || tag.equals("nn")) return "no";
        int dash = tag.indexOf('-');
        return dash > 0 ? tag.substring(0, dash) : tag;
    }

    private String detectOffline(String text) throws Exception {
        String sample = text.length() > 1000 ? text.substring(0, 1000) : text;
        String tag = norm(Tasks.await(langId().identifyLanguage(sample), 20, TimeUnit.SECONDS));
        if (!"und".equals(tag)) return tag;
        // fallback by script
        int cyr = 0, letters = 0;
        for (int i = 0; i < sample.length(); i++) {
            char ch = sample.charAt(i);
            if (Character.isLetter(ch)) {
                letters++;
                if (Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CYRILLIC) cyr++;
            }
        }
        return letters > 0 && cyr * 2 >= letters ? "ru" : "en";
    }

    private void offlineTranslate(String id, String text, String target) {
        pool.execute(() -> {
            boolean ok = false;
            String payload;
            try {
                String from = TranslateLanguage.fromLanguageTag(detectOffline(text));
                if (from == null) throw new Exception("unsupported:" + detectOffline(text));
                String to = TranslateLanguage.RUSSIAN.equals(from) ? TranslateLanguage.fromLanguageTag(target) : TranslateLanguage.RUSSIAN;
                if (to == null) throw new Exception("unsupported:" + target);
                if (from.equals(to)) {
                    payload = new JSONObject().put("from", from).put("to", to).put("text", text).toString();
                    ok = true;
                } else {
                    Set<String> have = downloadedModels();
                    LinkedHashSet<String> missing = new LinkedHashSet<>();
                    if (!have.contains(from) && !TranslateLanguage.ENGLISH.equals(from)) missing.add(from);
                    if (!have.contains(to) && !TranslateLanguage.ENGLISH.equals(to)) missing.add(to);
                    if (!missing.isEmpty()) throw new Exception("missing:" + String.join(",", missing));
                    Translator t = translator(from, to);
                    StringBuilder out = new StringBuilder();
                    String[] lines = text.split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i];
                        if (line.trim().isEmpty()) out.append(line);
                        else out.append(Tasks.await(t.translate(line), 60, TimeUnit.SECONDS));
                        if (i < lines.length - 1) out.append('\n');
                    }
                    payload = new JSONObject().put("from", from).put("to", to).put("text", out.toString()).toString();
                    ok = true;
                }
            } catch (Exception e) {
                payload = errMsg(e);
            }
            js("window.__offlineResult(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(payload) + ")");
        });
    }

    private void offlineDownload(String codesJson, boolean wifiOnly) {
        if (downloading) return;
        downloading = true;
        pool.execute(() -> {
            try {
                if (!isOnline()) throw new Exception("Нет интернета для скачивания.");
                if (wifiOnly && !onWifi()) throw new Exception("Подключитесь к Wi‑Fi или отключите «Скачивать только по Wi‑Fi».");
                LinkedHashSet<String> codes = new LinkedHashSet<>();
                codes.add(TranslateLanguage.RUSSIAN);
                codes.add(TranslateLanguage.ENGLISH);
                JSONArray a = new JSONArray(codesJson);
                for (int i = 0; i < a.length(); i++) {
                    String c = TranslateLanguage.fromLanguageTag(a.getString(i));
                    if (c != null) codes.add(c);
                }
                Set<String> have = downloadedModels();
                codes.removeAll(have);
                DownloadConditions cond = wifiOnly
                        ? new DownloadConditions.Builder().requireWifi().build()
                        : new DownloadConditions.Builder().build();
                int i = 0, total = codes.size();
                for (String c : codes) {
                    offlineEvent("progress", new JSONObject().put("code", c).put("index", i).put("total", total).toString());
                    Tasks.await(modelManager.download(new TranslateRemoteModel.Builder(c).build(), cond), 20, TimeUnit.MINUTES);
                    i++;
                }
                offlineEvent("done", downloadedJson());
            } catch (Exception e) {
                offlineEvent("error", errMsg(e));
                offlineEvent("list", downloadedJson());
            } finally {
                downloading = false;
            }
        });
    }

    private void offlineDelete(String code) {
        pool.execute(() -> {
            try {
                Tasks.await(modelManager.deleteDownloadedModel(new TranslateRemoteModel.Builder(code).build()), 60, TimeUnit.SECONDS);
                synchronized (translators) {
                    for (Iterator<Map.Entry<String, Translator>> it = translators.entrySet().iterator(); it.hasNext(); ) {
                        Map.Entry<String, Translator> en = it.next();
                        if (en.getKey().startsWith(code + ">") || en.getKey().endsWith(">" + code)) { en.getValue().close(); it.remove(); }
                    }
                }
            } catch (Exception e) {
                offlineEvent("error", errMsg(e));
            }
            offlineEvent("list", downloadedJson());
        });
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

        /* offline: results in window.__offlineResult(id, ok, payload); events in window.__offline(type, json) */
        @JavascriptInterface
        public String offlineSupported() {
            return new JSONArray(TranslateLanguage.getAllLanguages()).toString();
        }

        @JavascriptInterface
        public void offlineList() {
            pool.execute(() -> offlineEvent("list", downloadedJson()));
        }

        @JavascriptInterface
        public void offlineDownload(String codesJson, boolean wifiOnly) {
            MainActivity.this.offlineDownload(codesJson, wifiOnly);
        }

        @JavascriptInterface
        public void offlineDelete(String code) {
            MainActivity.this.offlineDelete(code);
        }

        @JavascriptInterface
        public void offlineTranslate(String id, String text, String target) {
            MainActivity.this.offlineTranslate(id, text, target);
        }

        /* photo: events in window.__photo(type, data): loading | result | error */
        @JavascriptInterface
        public void pickPhoto(final String source) {
            ui.post(() -> MainActivity.this.pickPhoto(source));
        }

        @JavascriptInterface
        public boolean isOnline() {
            return MainActivity.this.isOnline();
        }
    }
}
