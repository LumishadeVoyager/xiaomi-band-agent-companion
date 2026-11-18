package io.github.lumishadevoyager.bandagent;

import android.app.NotificationManager;
import android.content.Context;
import android.os.PowerManager;
import org.json.JSONObject;

/** Small operational receipt, without device identifiers or account data. */
public final class PhoneStatus {
    static boolean unrestricted(Context context) {
        return context.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(context.getPackageName());
    }
    static boolean notifications(Context context) {
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        android.app.NotificationChannel channel=manager.getNotificationChannel("powerprofile_codex_completed");
        return manager.areNotificationsEnabled() && (channel==null || channel.getImportance()!=NotificationManager.IMPORTANCE_NONE);
    }
    static JSONObject report(Context context,String session,long startedAt,long uptime,boolean connected) throws Exception {
        android.content.SharedPreferences state=context.getSharedPreferences("runtime",0);
        return new JSONObject().put("protocol",1).put("entryId","powerprofile").put("phoneVersion",ApkUpdateInstaller.current(context))
            .put("session",session).put("startedAt",startedAt).put("uptimeSeconds",uptime)
            .put("wearConnected",connected).put("batteryUnrestricted",unrestricted(context)).put("notificationsAllowed",notifications(context))
            .put("lastSyncAt",state.getLong("lastSyncAt",0)).put("lastWearAt",state.getLong("lastWearAt",0));
    }
}
