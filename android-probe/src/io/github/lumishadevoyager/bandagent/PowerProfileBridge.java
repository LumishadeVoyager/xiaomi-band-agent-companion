package io.github.lumishadevoyager.bandagent;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import com.xiaomi.xms.wearable.Wearable;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** System notifications remain available when the wearable page is closed. */
public final class PowerProfileBridge {
    private static final String CHANNEL = "powerprofile_codex_completed";
    private final Service service;
    private final NotificationManager notifications;
    private final SharedPreferences prefs;
    private String statusText = "等待电脑统计";
    private boolean sourceOnline;
    private long sourceAt;
    public boolean sourceOnline(){return sourceOnline;}
    public long sourceAt(){return sourceAt;}
    public String statusText() { return statusText; }
    public PowerProfileBridge(Service service) {
        this.service = service;
        prefs = service.getSharedPreferences("powerprofile", 0);
        notifications = service.getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Codex 完成提醒", NotificationManager.IMPORTANCE_DEFAULT));
    }
    public void testNotification() throws Exception {
        if (service.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED || !notifications.areNotificationsEnabled()) throw new java.io.IOException("notifications_disabled");
        NotificationChannel channel = notifications.getNotificationChannel(CHANNEL);
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) throw new java.io.IOException("notification_channel_disabled");
        // Explicit diagnostic, never a synthetic Codex completion or delivery ACK.
        notifications.notify("powerprofile_acceptance_test", 3, new Notification.Builder(service, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("PowerProfile 通知测试")
            .setContentText("这是一条手机到手环的通知链路验收测试").setAutoCancel(true).build());
    }
    public void poll(ComputerLink computer, String nodeId) throws Exception {
        if (!computer.paired()) return;
        JSONObject identity = new JSONObject().put("protocol", 1).put("entryId", "powerprofile");
        JSONObject result = computer.post("/powerprofile/poll", identity);
        JSONObject relay = result.optJSONObject("relay");
        sourceOnline=relay!=null&&relay.optBoolean("sourceOnline");sourceAt=0;
        if(relay!=null)try{sourceAt=java.time.Instant.parse(relay.optString("lastUploadAt")).toEpochMilli();}catch(Exception ignored){}
        statusText = computer.internet() ? (relay != null && relay.optBoolean("sourceOnline") ? "互联网中继已同步；手环显示以设备回执为准" : "互联网中继：电脑离线，保留上次统计") : "电脑统计已读取；手环显示以设备回执为准";
        JSONObject snapshot = result.getJSONObject("snapshot");
        prefs.edit().putString("snapshot", snapshot.toString()).apply();
        if (nodeId != null) Wearable.getMessageApi(service).sendMessage(nodeId, snapshot.toString().getBytes(StandardCharsets.UTF_8));
        JSONArray events = result.getJSONArray("events"), accepted = new JSONArray();
        if (service.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED || !notifications.areNotificationsEnabled()) return;
        NotificationChannel channel = notifications.getNotificationChannel(CHANNEL);
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return;
        java.util.Set<String> delivered = new java.util.HashSet<>(prefs.getStringSet("delivered", java.util.Collections.emptySet()));
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i); String id = event.getString("id");
            if (!id.matches("[a-f0-9]{64}")) continue;
            if (!delivered.contains(id)) {
                PendingIntent open = PendingIntent.getActivity(service, 2, new Intent(service, BridgeActivity.class), PendingIntent.FLAG_IMMUTABLE);
                notifications.notify(id, 2, new Notification.Builder(service, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("Codex 本轮已完成")
                    .setContentText("请到电脑查看结果").setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build());
                delivered.add(id);
                // Commit before computer ACK; retries do not alert again.
                if (!prefs.edit().putStringSet("delivered", delivered).commit()) throw new java.io.IOException("notification_receipt_not_saved");
            }
            accepted.put(id);
        }
        if (accepted.length() > 0) computer.post("/powerprofile/ack", identity.put("ids", accepted));
        // Bounded receipts; server expires old events after 24 hours.
        if (delivered.size() > 2000) { delivered.retainAll(java.util.stream.IntStream.range(0, events.length()).mapToObj(i -> events.optJSONObject(i).optString("id")).collect(java.util.stream.Collectors.toSet())); prefs.edit().putStringSet("delivered", delivered).commit(); }
    }
}
