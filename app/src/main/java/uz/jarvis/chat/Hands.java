package uz.jarvis.chat;

import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public class Hands {

    static String[] findApp(Context c, String name) {
        if (name == null) return null;
        String q = name.toLowerCase(Locale.ROOT).trim();
        if (q.length() == 0) return null;
        PackageManager pm = c.getPackageManager();
        Intent li = new Intent(Intent.ACTION_MAIN);
        li.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(li, 0);
        String[] best = null;
        for (ResolveInfo r : list) {
            String lab = String.valueOf(r.loadLabel(pm));
            String low = lab.toLowerCase(Locale.ROOT);
            String pkg = r.activityInfo.packageName;
            if (low.equals(q)) return new String[]{pkg, lab};
            if (best == null && (low.contains(q) || (low.length() >= 4 && q.contains(low)))) {
                best = new String[]{pkg, lab};
            }
        }
        return best;
    }

    static String openApp(Context c, String name) {
        try {
            String[] a = findApp(c, name);
            if (a == null) return "XATO: \"" + name + "\" ilovasi topilmadi.";
            Intent i = c.getPackageManager().getLaunchIntentForPackage(a[0]);
            if (i == null) return "XATO: " + a[1] + " ochilmadi.";
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return "OK: " + a[1] + " ochildi.";
        } catch (Exception e) {
            return "XATO: " + e.getMessage();
        }
    }

    static String dial(Context c, String to) {
        if (to == null || to.trim().length() == 0) return "XATO: kimga ekani aytilmadi.";
        String number = to.replaceAll("[^0-9+]", "");
        String who = to.trim();
        if (number.replaceAll("[^0-9]", "").length() < 5) {
            if (c.checkSelfPermission("android.permission.READ_CONTACTS") != PackageManager.PERMISSION_GRANTED) {
                return "XATO: kontaktlarga ruxsat yo'q.";
            }
            number = null;
            Cursor cur = null;
            try {
                cur = c.getContentResolver().query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER,
                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME},
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                        new String[]{"%" + to.trim() + "%"}, null);
                if (cur != null && cur.moveToFirst()) {
                    number = cur.getString(0);
                    who = cur.getString(1);
                }
            } catch (Exception e) {
                return "XATO: " + e.getMessage();
            } finally {
                if (cur != null) cur.close();
            }
            if (number == null) return "XATO: \"" + to + "\" kontaktlar orasida topilmadi.";
        }
        try {
            Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return "OK: " + who + " raqami terildi (qo'ng'iroq tugmasi bosilmagan).";
        } catch (Exception e) {
            return "XATO: " + e.getMessage();
        }
    }

    static String url(Context c, String u) {
        if (u == null) return "XATO: havola yo'q.";
        String l = u.trim().toLowerCase(Locale.ROOT);
        if (!(l.startsWith("http://") || l.startsWith("https://") || l.startsWith("tg:"))) {
            return "XATO: bu havola turi ruxsat etilmagan.";
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u.trim()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return "OK: havola ochildi.";
        } catch (Exception e) {
            return "XATO: " + e.getMessage();
        }
    }

    static String alarm(Context c, int h, int m, String label) {
        try {
            Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
            i.putExtra(AlarmClock.EXTRA_HOUR, h);
            i.putExtra(AlarmClock.EXTRA_MINUTES, m);
            i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return "OK: budilnik " + h + ":" + (m < 10 ? "0" : "") + m + " ga qo'yildi.";
        } catch (Exception e) {
            return "XATO: " + e.getMessage();
        }
    }

    static String timer(Context c, int sec, String label) {
        try {
            Intent i = new Intent(AlarmClock.ACTION_SET_TIMER);
            i.putExtra(AlarmClock.EXTRA_LENGTH, sec);
            i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return "OK: taymer " + sec + " soniyaga qo'yildi.";
        } catch (Exception e) {
            return "XATO: " + e.getMessage();
        }
    }

    static String torch(Context c, boolean on) {
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (f != null && f) {
                    cm.setTorchMode(id, on);
                    return "OK: chiroq " + (on ? "yoqildi." : "o'chirildi.");
                }
            }
            return "XATO: chiroq topilmadi.";
        } catch (Exception e) {
            return "XATO: " + e.getMessage();
        }
    }

    static String shareLatest(Context c, String kind, String app) {
        boolean video = !"image".equals(kind);
        String perm = Build.VERSION.SDK_INT >= 33
                ? (video ? "android.permission.READ_MEDIA_VIDEO" : "android.permission.READ_MEDIA_IMAGES")
                : "android.permission.READ_EXTERNAL_STORAGE";
        if (c.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            return "XATO: galereyaga ruxsat yo'q.";
        }
        Uri coll = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri u = null;
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(coll, new String[]{MediaStore.MediaColumns._ID},
                    null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC");
            if (cur != null && cur.moveToFirst()) u = ContentUris.withAppendedId(coll, cur.getLong(0));
        } catch (Exception e) {
            return "XATO: " + e.getMessage();
        } finally {
            if (cur != null) cur.close();
        }
        if (u == null) return "XATO: galereyada " + (video ? "video" : "rasm") + " topilmadi.";
        String[] a = findApp(c, app);
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType(video ? "video/*" : "image/*");
        s.putExtra(Intent.EXTRA_STREAM, u);
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (a != null) s.setPackage(a[0]);
        try {
            c.startActivity(s);
        } catch (ActivityNotFoundException e) {
            return "XATO: " + app + " ulashishni qabul qilmadi.";
        } catch (Exception e) {
            return "XATO: " + e.getMessage();
        }
        return "OK: eng oxirgi " + (video ? "video" : "rasm") + " " + (a != null ? a[1] : "ilova") + " ga ulashish oynasida ochildi.";
    }

    static String[] gemini(String key, String model, String body) {
        try {
            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent");
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
            return new String[]{String.valueOf(code), sb.toString()};
        } catch (Exception e) {
            return new String[]{"0", String.valueOf(e.getMessage())};
        }
    }
}
