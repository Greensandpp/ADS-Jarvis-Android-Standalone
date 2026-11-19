package com.adsjarvis.standalone;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.AlarmClock;
import android.provider.Settings;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Executes CommandEngine results using only permission-safe Android APIs:
 * public intents (the target app shows its own confirmation), launcher
 * queries declared in the manifest, torch, media volume and battery state.
 * No accessibility, overlay, contacts, SMS, call or payment capability.
 */
final class DeviceActions {
    private final Context ctx;
    DeviceActions(Context ctx) { this.ctx = ctx; }

    /** Returns the text to show/speak; may replace the engine's default reply. */
    String run(CommandEngine.Result r) {
        try {
            switch (r.action) {
                case OPEN_SETTINGS: return start(new Intent(Settings.ACTION_SETTINGS), r.response);
                case OPEN_WIFI:
                    return start(new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS), r.response);
                case OPEN_BLUETOOTH: return start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), r.response);
                case OPEN_APP: return openApp(r.argument, r.response);
                case SET_ALARM:
                    return start(new Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, r.hour).putExtra(AlarmClock.EXTRA_MINUTES, r.minute)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS").putExtra(AlarmClock.EXTRA_SKIP_UI, false), r.response);
                case SET_TIMER:
                    return start(new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, r.hour)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS").putExtra(AlarmClock.EXTRA_SKIP_UI, false), r.response);
                case DIAL: return start(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(r.argument))), r.response);
                case WEB_SEARCH:
                    return start(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=" + Uri.encode(r.argument))), r.response);
                case TORCH_ON: return torch(true, r.response);
                case TORCH_OFF: return torch(false, r.response);
                case VOLUME_UP: return volume(AudioManager.ADJUST_RAISE, r.response);
                case VOLUME_DOWN: return volume(AudioManager.ADJUST_LOWER, r.response);
                case BATTERY: {
                    BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
                    int pct = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    boolean charging = bm != null && bm.isCharging();
                    if (pct < 0) return "Battery level is not available on this device.";
                    return r.lang == CommandEngine.Lang.TAMIL ? "பேட்டரி " + pct + "%" + (charging ? ", சார்ஜ் ஆகிறது." : ".")
                        : "Battery is at " + pct + "%" + (charging ? " and charging." : ".");
                }
                case TIME: {
                    String now = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(new Date());
                    return r.lang == CommandEngine.Lang.TAMIL ? "இப்போது நேரம் " + now : "It's " + now + ".";
                }
                case DATE: {
                    String today = DateFormat.getDateInstance(DateFormat.FULL, Locale.getDefault()).format(new Date());
                    return r.lang == CommandEngine.Lang.TAMIL ? "இன்று " + today : "Today is " + today + ".";
                }
                default: return r.response;
            }
        } catch (SecurityException e) {
            return "Android blocked that action on this phone.";
        }
    }

    private String start(Intent i, String ok) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); return ok; }
        catch (ActivityNotFoundException e) { return "No app on this phone can handle that."; }
    }

    /** Finds a launchable app by label among apps visible via the manifest <queries>. */
    String openApp(String name, String ok) {
        PackageManager pm = ctx.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);
        String want = name.toLowerCase(Locale.ROOT).replace(" ", "");
        ResolveInfo best = null;
        for (ResolveInfo ri : apps) {
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT).replace(" ", "");
            if (label.equals(want)) { best = ri; break; }
            if (best == null && (label.startsWith(want) || label.contains(want))) best = ri;
        }
        if (best == null) return "I couldn't find an app called \"" + name + "\" on this phone.";
        Intent li = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        if (li == null) return "That app can't be opened directly.";
        return start(li, ok);
    }

    private String torch(boolean on, String ok) {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) { cm.setTorchMode(id, on); return ok; }
            }
            return "This device has no flashlight.";
        } catch (Exception e) {
            return "The flashlight is busy or unavailable.";
        }
    }

    private String volume(int dir, String ok) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "Volume control unavailable.";
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
        return ok;
    }
}
