package uz.jarvis.chat;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
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

public class MainActivity extends Activity {
    private WebView web;
    private SharedPreferences prefs;

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
    }

    private void deliver(final String id, final int status, final String text) {
        final String b64 = Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript("onNativeResult('" + id + "'," + status + ",'" + b64 + "')", null);
            }
        });
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
    public void onBackPressed() {
        super.onBackPressed();
    }
}
