package uz.jarvis.chat;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

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
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_VOICE = 101;
    static MainActivity inst;
    private Bridge bridge;

    private WebView web;
    private SharedPreferences prefs;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String lastUtt = "";

    // ElevenLabs
    private static final int REQ_MIC = 202;
    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private int elGen = 0;
    private MediaRecorder rec;
    private File recFile;
    private boolean recActive = false, recSpoke = false, pendingListen = false;
    private long recStart, lastLoud;
    private int pollN = 0, noise = 0;
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!recActive) return;
            int amp = 0;
            try {
                amp = rec.getMaxAmplitude();
            } catch (Exception e) {
                // ignore
            }
            long now = SystemClock.elapsedRealtime();
            pollN++;
            if (pollN <= 10 && amp > 0 && (noise == 0 || amp < noise)) noise = amp;
            int thr = Math.max(900, noise * 3);
            js("onLevel(" + amp + ")");
            if (amp > thr) {
                recSpoke = true;
                lastLoud = now;
            }
            boolean done = false;
            if (recSpoke && now - lastLoud > 1500) done = true;
            else if (!recSpoke && now - recStart > 8000) done = true;
            else if (now - recStart > 30000) done = true;
            if (done) finishRec(true, false);
            else h.postDelayed(this, 100);
        }
    };

    private String b64(String t) {
        return Base64.encodeToString(String.valueOf(t).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private void elError(String kind, String msg) {
        js("onElError('" + kind + "','" + b64(msg) + "')");
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }

    private void stopPlayer() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (player != null) {
                    try {
                        player.stop();
                    } catch (Exception e) {
                        // ignore
                    }
                    try {
                        player.release();
                    } catch (Exception e) {
                        // ignore
                    }
                    player = null;
                }
            }
        });
    }

    private void playFile(final String path, final int gen) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (gen != elGen) return;
                try {
                    if (player != null) {
                        try { player.release(); } catch (Exception e) { /* ignore */ }
                    }
                    final MediaPlayer mp = new MediaPlayer();
                    player = mp;
                    mp.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                    mp.setDataSource(path);
                    mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer m) {
                            if (gen != elGen || player != mp) return;
                            m.start();
                            js("onSpeakState('start')");
                        }
                    });
                    mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        @Override
                        public void onCompletion(MediaPlayer m) {
                            if (player == mp) {
                                try { mp.release(); } catch (Exception e) { /* ignore */ }
                                player = null;
                            }
                            if (gen == elGen) js("onSpeakState('done')");
                        }
                    });
                    mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        @Override
                        public boolean onError(MediaPlayer m, int what, int extra) {
                            if (gen == elGen) elError("play", "Audio o'ynatib bo'lmadi (" + what + ")");
                            return true;
                        }
                    });
                    mp.prepareAsync();
                } catch (Exception e) {
                    elError("play", String.valueOf(e.getMessage()));
                }
            }
        });
    }

    private void startRec() {
        try {
            recFile = new File(getCacheDir(), "rec.m4a");
            rec = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioEncodingBitRate(64000);
            rec.setAudioSamplingRate(16000);
            rec.setOutputFile(recFile.getAbsolutePath());
            rec.prepare();
            rec.start();
            long now = SystemClock.elapsedRealtime();
            pollN = 0;
            noise = 0;
            recActive = true;
            recSpoke = false;
            recStart = now;
            lastLoud = now;
            h.postDelayed(poll, 100);
        } catch (Exception e) {
            recActive = false;
            try { if (rec != null) rec.release(); } catch (Exception x) { /* ignore */ }
            rec = null;
            elError("stt", "Mikrofonni ochib bo'lmadi: " + e.getMessage());
        }
    }

    private void finishRec(boolean upload, boolean manual) {
        if (!recActive) return;
        recActive = false;
        h.removeCallbacks(poll);
        boolean ok = true;
        try {
            rec.stop();
        } catch (RuntimeException e) {
            ok = false;
        }
        try { rec.release(); } catch (Exception e) { /* ignore */ }
        rec = null;
        if (!upload) return;
        if (!ok || !(recSpoke || manual)) {
            js("onVoiceError('silence')");
            return;
        }
        transcribe();
    }

    private void transcribe() {
        js("setState('think')");
        final File f = recFile;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String key = prefs.getString("elkey", "");
                    String model = prefs.getString("stt_model", "scribe_v1");
                    String bd = "----jv" + System.currentTimeMillis();
                    HttpURLConnection c = (HttpURLConnection) new URL("https://api.elevenlabs.io/v1/speech-to-text").openConnection();
                    c.setRequestMethod("POST");
                    c.setRequestProperty("xi-api-key", key);
                    c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + bd);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(60000);
                    c.setDoOutput(true);
                    OutputStream o = c.getOutputStream();
                    String[][] fields = {{"model_id", model}, {"tag_audio_events", "false"}};
                    for (String[] kv : fields) {
                        o.write(("--" + bd + "\r\nContent-Disposition: form-data; name=\"" + kv[0] + "\"\r\n\r\n" + kv[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
                    }
                    o.write(("--" + bd + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"rec.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    FileInputStream fi = new FileInputStream(f);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fi.read(buf)) > 0) o.write(buf, 0, n);
                    fi.close();
                    o.write(("\r\n--" + bd + "--\r\n").getBytes(StandardCharsets.UTF_8));
                    o.close();
                    int code = c.getResponseCode();
                    String resp = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
                    if (code != 200) {
                        elError("stt", "ElevenLabs STT xato " + code + ": " + (resp.length() > 300 ? resp.substring(0, 300) : resp));
                        return;
                    }
                    String text = new JSONObject(resp).optString("text", "").trim();
                    if (text.length() == 0) {
                        js("onVoiceError('empty')");
                        return;
                    }
                    js("onVoice('" + b64(text) + "')");
                } catch (Exception e) {
                    elError("stt", String.valueOf(e.getMessage()));
                }
            }
        }).start();
    }

    private ArrayList<String> listVoices(String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.elevenlabs.io/v1/voices").openConnection();
        c.setRequestProperty("xi-api-key", key);
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        int code = c.getResponseCode();
        String resp = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        ArrayList<String> out = new ArrayList<String>();
        if (code != 200) return out;
        JSONArray arr = new JSONObject(resp).optJSONArray("voices");
        if (arr == null) return out;
        String[] order = {"generated", "cloned", "premade", ""};
        for (String cat : order) {
            for (int i = 0; i < arr.length() && out.size() < 6; i++) {
                JSONObject v = arr.getJSONObject(i);
                String vc = v.optString("category", "");
                String id = v.optString("voice_id", "");
                boolean match = cat.length() == 0 ? true : vc.equals(cat);
                if (match && id.length() > 0 && !out.contains(id)) out.add(id);
            }
        }
        return out;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && pendingListen) startRec();
        else js("onVoiceError('perm')");
        pendingListen = false;
    }


    // ---------- telefonni boshqarish yordamchilari ----------

    void sayFromService(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (prefs.getString("speak", "1").equals("0")) return;
                if (prefs.getString("elkey", "").length() > 0) {
                    bridge.speakEL(text);
                } else {
                    String lg = prefs.getString("lang", "");
                    bridge.speak(text, lg.length() > 0 ? lg : "uz-UZ");
                }
            }
        });
    }

    void agentDone(final String text) {
        js("onAgentDone('" + b64(text) + "')");
        sayFromService(text);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show();
            }
        });
    }

    private boolean hasPerm(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPerms(final String[] perms) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                requestPermissions(perms, 203);
            }
        });
    }

    private String runQuick(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        String a = o.optString("a", "");
        if (a.equals("open")) return Hands.openApp(this, o.optString("app"));
        if (a.equals("dial")) {
            String to = o.optString("to");
            boolean digits = to.replaceAll("[^0-9]", "").length() >= 5;
            if (!digits && !hasPerm("android.permission.READ_CONTACTS")) {
                askPerms(new String[]{"android.permission.READ_CONTACTS"});
                return "XATO: kontaktlarga ruxsat so'raldi. Ruxsat berib, buyruqni qayta ayting.";
            }
            return Hands.dial(this, to);
        }
        if (a.equals("alarm")) return Hands.alarm(this, o.optInt("h"), o.optInt("m"), o.optString("label", "Jarvis"));
        if (a.equals("timer")) return Hands.timer(this, o.optInt("sec", 60), o.optString("label", "Jarvis"));
        if (a.equals("torch")) return Hands.torch(this, o.optBoolean("on", true));
        if (a.equals("url")) return Hands.url(this, o.optString("url"));
        if (a.equals("agent")) {
            JarvisAccessibility s = JarvisAccessibility.inst;
            if (s == null) return "OFF";
            ArrayList<String> need = new ArrayList<String>();
            if (!hasPerm("android.permission.READ_CONTACTS")) need.add("android.permission.READ_CONTACTS");
            if (Build.VERSION.SDK_INT >= 33) {
                if (!hasPerm("android.permission.READ_MEDIA_VIDEO")) need.add("android.permission.READ_MEDIA_VIDEO");
                if (!hasPerm("android.permission.READ_MEDIA_IMAGES")) need.add("android.permission.READ_MEDIA_IMAGES");
            } else if (!hasPerm("android.permission.READ_EXTERNAL_STORAGE")) {
                need.add("android.permission.READ_EXTERNAL_STORAGE");
            }
            if (!need.isEmpty()) {
                askPerms(need.toArray(new String[0]));
                return "XATO: kontakt va galereya ruxsatlari so'raldi. Hammasiga ruxsat berib, buyruqni qayta ayting.";
            }
            if (s.isRunning()) return "XATO: avvalgi vazifa hali ketyapti.";
            s.start(o.optString("goal"));
            return "OK";
        }
        return "XATO: noma'lum amal.";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        inst = this;
        bridge = new Bridge();
        web.addJavascriptInterface(bridge, "Native");
        web.loadUrl("file:///android_asset/index.html");
        initTts("com.google.android.tts");
    }

    private void initTts(final String engine) {
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true;
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String utteranceId) {
                            js("onSpeakState('start')");
                        }

                        @Override
                        public void onDone(String utteranceId) {
                            if (utteranceId != null && utteranceId.equals(lastUtt)) {
                                js("onSpeakState('done')");
                            }
                        }

                        @Override
                        public void onError(String utteranceId) {
                            js("onSpeakState('done')");
                        }
                    });
                } else if (engine != null) {
                    try {
                        tts.shutdown();
                    } catch (Exception e) {
                        // ignore
                    }
                    initTts(null);
                }
            }
        }, engine);
    }

    void js(final String code) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(code, null);
            }
        });
    }

    private void deliver(String id, int status, String text) {
        String b64 = Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        js("onNativeResult('" + id + "'," + status + ",'" + b64 + "')");
    }

    private class Bridge {
        @JavascriptInterface
        public String get(String key, String def) {
            return prefs.getString(key, def);
        }

        @JavascriptInterface
        public void set(String key, String value) {
            prefs.edit().putString(key, value).apply();
        }

        @JavascriptInterface
        public void listen(final String lang) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
                        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Gapiring...");
                        startActivityForResult(i, REQ_VOICE);
                    } catch (ActivityNotFoundException e) {
                        web.evaluateJavascript("onVoiceError('no_service')", null);
                    }
                }
            });
        }

        @JavascriptInterface
        public void speak(final String text, final String lang) {
            if (tts == null || !ttsReady) {
                js("onTtsError('notready')");
                return;
            }
            int r = tts.setLanguage(Locale.forLanguageTag(lang));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                js("onTtsError('lang')");
                return;
            }
            ArrayList<String> chunks = new ArrayList<String>();
            int max = Math.max(500, TextToSpeech.getMaxSpeechInputLength() - 100);
            int pos = 0;
            while (pos < text.length()) {
                int end = Math.min(text.length(), pos + max);
                if (end < text.length()) {
                    int sp = text.lastIndexOf(' ', end);
                    if (sp > pos) end = sp;
                }
                String c = text.substring(pos, end).trim();
                pos = end;
                if (c.length() > 0) chunks.add(c);
            }
            if (chunks.isEmpty()) return;
            lastUtt = "u" + (chunks.size() - 1);
            tts.stop();
            for (int i = 0; i < chunks.size(); i++) {
                tts.speak(chunks.get(i),
                        i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                        null, "u" + i);
            }
        }

        @JavascriptInterface
        public void stopSpeak() {
            elGen++;
            stopPlayer();
            if (tts != null && ttsReady) tts.stop();
            js("onSpeakState('done')");
        }

        @JavascriptInterface
        public String quick(final String json) {
            try {
                return runQuick(json);
            } catch (Exception e) {
                return "XATO: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public boolean a11yOn() {
            return JarvisAccessibility.inst != null;
        }

        @JavascriptInterface
        public void openA11y() {
            try {
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                // ignore
            }
        }

        @JavascriptInterface
        public void speakEL(final String text) {
            final int gen = ++elGen;
            stopPlayer();
            if (tts != null && ttsReady) tts.stop();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String key = prefs.getString("elkey", "");
                        String manual = prefs.getString("elvoice", "");
                        String model = prefs.getString("elmodel", "");
                        if (model.length() == 0) model = "eleven_v3";
                        String t = text.length() > 4500 ? text.substring(0, 4500) : text;
                        JSONObject body = new JSONObject();
                        body.put("text", t);
                        body.put("model_id", model);
                        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

                        ArrayList<String> cands = new ArrayList<String>();
                        if (manual.length() > 0) {
                            cands.add(manual);
                        } else {
                            String ok = prefs.getString("elvoice_ok", "");
                            if (ok.length() > 0) cands.add(ok);
                            else cands.addAll(listVoices(key));
                        }
                        if (cands.isEmpty()) {
                            elError("tts", "ElevenLabs hisobida ovoz topilmadi. Saytda Voices bo'limidan ovoz qo'shing yoki Voice ID kiriting.");
                            return;
                        }
                        String lastErr = "";
                        for (String voice : cands) {
                            if (gen != elGen) return;
                            URL url = new URL("https://api.elevenlabs.io/v1/text-to-speech/" + voice + "?output_format=mp3_44100_64");
                            HttpURLConnection c = (HttpURLConnection) url.openConnection();
                            c.setRequestMethod("POST");
                            c.setRequestProperty("xi-api-key", key);
                            c.setRequestProperty("Content-Type", "application/json");
                            c.setRequestProperty("Accept", "audio/mpeg");
                            c.setConnectTimeout(20000);
                            c.setReadTimeout(60000);
                            c.setDoOutput(true);
                            OutputStream o = c.getOutputStream();
                            o.write(payload);
                            o.close();
                            int code = c.getResponseCode();
                            if (code != 200) {
                                String resp = readAll(c.getErrorStream());
                                lastErr = "ElevenLabs ovoz xato " + code + ": " + (resp.length() > 300 ? resp.substring(0, 300) : resp);
                                if (manual.length() == 0) prefs.edit().remove("elvoice_ok").apply();
                                if (code == 402 || code == 404 || code == 422) continue;
                                break;
                            }
                            if (manual.length() == 0) prefs.edit().putString("elvoice_ok", voice).apply();
                            File out = new File(getCacheDir(), "tts_" + gen + ".mp3");
                            InputStream is = c.getInputStream();
                            FileOutputStream fo = new FileOutputStream(out);
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = is.read(buf)) > 0) fo.write(buf, 0, n);
                            fo.close();
                            is.close();
                            if (gen != elGen) return;
                            playFile(out.getAbsolutePath(), gen);
                            return;
                        }
                        elError("tts", lastErr);
                    } catch (Exception e) {
                        elError("tts", String.valueOf(e.getMessage()));
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void listenEL() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (recActive) return;
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        pendingListen = true;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                        return;
                    }
                    startRec();
                }
            });
        }

        @JavascriptInterface
        public void stopListen(final boolean send) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!recActive) {
                        js("onVoiceError('cancel')");
                        return;
                    }
                    finishRec(send, true);
                }
            });
        }

        @JavascriptInterface
        public void ask(final String id, final String model, final String body) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String key = prefs.getString("key", "");
                        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                                + model + ":generateContent");
                        HttpURLConnection c = (HttpURLConnection) url.openConnection();
                        c.setRequestMethod("POST");
                        c.setRequestProperty("Content-Type", "application/json");
                        c.setRequestProperty("x-goog-api-key", key);
                        c.setConnectTimeout(20000);
                        c.setReadTimeout(90000);
                        c.setDoOutput(true);
                        OutputStream o = c.getOutputStream();
                        o.write(body.getBytes(StandardCharsets.UTF_8));
                        o.close();
                        int code = c.getResponseCode();
                        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                        StringBuilder sb = new StringBuilder();
                        if (is != null) {
                            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                            String line;
                            while ((line = r.readLine()) != null) sb.append(line).append('\n');
                            r.close();
                        }
                        deliver(id, code, sb.toString());
                    } catch (Exception e) {
                        deliver(id, 0, String.valueOf(e.getMessage()));
                    }
                }
            }).start();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VOICE) return;
        if (resultCode == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) {
                String b64 = Base64.encodeToString(r.get(0).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                web.evaluateJavascript("onVoice('" + b64 + "')", null);
                return;
            }
        }
        web.evaluateJavascript("onVoiceError('cancel')", null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (recActive) finishRec(false, false);
    }

    @Override
    protected void onDestroy() {
        if (inst == this) inst = null;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
