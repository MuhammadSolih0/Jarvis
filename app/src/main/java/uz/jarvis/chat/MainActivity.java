package uz.jarvis.chat;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.BufferedReader;
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

    private WebView web;
    private SharedPreferences prefs;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String lastUtt = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Native");
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

    private void js(final String code) {
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
            if (tts != null && ttsReady) tts.stop();
            js("onSpeakState('done')");
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
        if (tts != null && ttsReady) tts.stop();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
