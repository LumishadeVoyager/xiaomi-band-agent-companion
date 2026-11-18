package io.github.lumishadevoyager.bandagent;

import android.app.*;
import android.content.*;
import android.os.*;
import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Owns the SDK listener across Activity closure and application updates. */
public final class BridgeService extends Service implements OtaCoordinator.Sink {
    public static final String CHANNEL = "band_agent_bridge";
    private final ScheduledExecutorService work = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor();
    private String entry, token, nodeId;
    private volatile boolean recording;
    private boolean registered, registering;
    private OtaCoordinator updates;
    private DiagnosticStore diagnostics;
    private ComputerLink computer;
    private PowerProfileBridge powerprofile;
    private String previousState = "";
    private final OnMessageReceivedListener listener = (id, bytes) -> {
        if (bytes.length > 8192) return;
        // Mark active recording before a queued update can start.
        try { JSONObject message = new JSONObject(new String(bytes, StandardCharsets.UTF_8)); if (entry.equals(message.optString("entryId")) && message.optInt("protocol") == 1) { if ("audio_begin".equals(message.optString("type"))) recording = true; } } catch (Exception ignored) {}
        work.execute(() -> receive(id, bytes));
    };
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "手环连接与更新", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, BridgeActivity.class), PendingIntent.FLAG_IMMUTABLE);
        try { startForeground(1, new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Band Agent 连接服务").setContentText("保持手环消息与自动更新连接").setContentIntent(open).setOngoing(true).build()); }
        catch (SecurityException error) { getSharedPreferences("ota", 0).edit().putString("lastStatus", "后台连接权限未满足，请打开手机伴侣完成设置").apply(); stopSelf(); return; }
        try {
            JSONObject config = new JSONObject(new String(OtaCoordinator.read(getAssets().open("development.json"), 8192), StandardCharsets.UTF_8));
            entry = config.getString("entryId"); token = config.optString("token", ""); updates = new OtaCoordinator(this, entry, this); diagnostics = new DiagnosticStore(this); computer = new ComputerLink(this);
            if ("powerprofile".equals(entry)) powerprofile = new PowerProfileBridge(this);
            else diagnostics.phone(entry, ApkUpdateInstaller.current(this), "phone_service_started", new JSONObject().put("phase", "started"), false);
        } catch (Exception error) { state("error", "更新配置加载失败：" + error.getClass().getSimpleName(), 0); stopSelf(); return; }
        if (powerprofile == null) {
            maintenance.scheduleWithFixedDelay(() -> updates.check(true, true), 20, 21600, TimeUnit.SECONDS);
            maintenance.scheduleWithFixedDelay(() -> updates.poll(), 5, 5, TimeUnit.SECONDS);
        }
        work.scheduleWithFixedDelay(this::findNode, 5, 30, TimeUnit.SECONDS);
        maintenance.scheduleWithFixedDelay(this::uploadDiagnostics, 3, 10, TimeUnit.SECONDS);
        maintenance.scheduleWithFixedDelay(this::pollComputer, 7, 15, TimeUnit.SECONDS);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (updates == null) { stopSelf(); return START_NOT_STICKY; }
        if (intent != null) {
            String supplied = intent.getStringExtra("nodeId");
            if (supplied != null) nodeId = supplied;
            if ("check".equals(intent.getAction()) && updates != null) { if (powerprofile == null) maintenance.execute(() -> updates.check(true, false)); else state("preview", "PowerProfile 预览尚未发布 OTA", 0); }
            if ("reload_link".equals(intent.getAction())) maintenance.execute(() -> { try { computer = new ComputerLink(this); uploadDiagnostics(); } catch (Exception error) { state("error", "电脑配对加载失败", 0); } });
        }
        findNode(); return START_STICKY;
    }
    private void findNode() {
        if (registered || registering) return;
        registering = true;
        Wearable.getNodeApi(this).getConnectedNodes().addOnSuccessListener(nodes -> {
            if (nodes.size() != 1) { registering = false; state("disconnected", "等待小米运动健康连接唯一手环", 0); return; }
            nodeId = nodes.get(0).id;
            Wearable.getMessageApi(this).addListener(nodeId, listener)
                .addOnSuccessListener(ignored -> { registered = true; registering = false; state("connected", "后台监听就绪", 0); })
                .addOnFailureListener(error -> { registering = false; state("error", "手环消息授权待完成", 0); });
        }).addOnFailureListener(error -> { registering = false; });
    }
    public boolean busy() { return recording; }
    public void state(String phase, String text, int progress) {
        android.util.Log.i("BandAgentBridge", "phase=" + phase + " " + text);
        try { String key = phase + ":" + text; if (powerprofile == null && diagnostics != null && !key.equals(previousState)) { previousState = key; diagnostics.phone(entry, ApkUpdateInstaller.current(this), "bridge_" + phase.replaceAll("[^a-z0-9_]", ""), new JSONObject().put("phase", phase).put("errorCode", phase.equals("error") ? text.substring(0, Math.min(100, text.length())) : ""), phase.equals("error")); } } catch (Exception ignored) {}
        getSharedPreferences("ota", 0).edit().putString("lastStatus", text).putString("phase", phase).apply();
        sendBroadcast(new Intent(getPackageName() + ".STATUS").setPackage(getPackageName()).putExtra("text", text));
        if (nodeId != null && entry != null) {
            try { send(nodeId, new JSONObject().put("protocol", 1).put("type", "ota_status").put("entryId", entry).put("phase", phase).put("text", text).put("progress", progress)); } catch (Exception ignored) {}
        }
    }
    private void send(String destination, JSONObject message) { Wearable.getMessageApi(this).sendMessage(destination, message.toString().getBytes(StandardCharsets.UTF_8)); }
    private void receive(String source, byte[] bytes) {
        try {
            JSONObject message = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!entry.equals(message.optString("entryId")) || message.optInt("protocol") != 1) return;
            String type = message.optString("type"); nodeId = source;
            if (powerprofile != null) { if ("powerprofile_request".equals(type)) maintenance.execute(this::pollComputer); return; }
            if (type.equals("probe_capabilities")) {
                String report = "手环 " + message.optString("version") + " 接口诊断：" + message.optJSONObject("record");
                android.util.Log.i("BandAgentProbe", report); state("diagnostic", report, 0); return;
            }
            if (type.equals("ota_hello")) { long code = message.getLong("versionCode"); maintenance.execute(() -> updates.hello(code)); return; }
            if (type.equals("ota_check")) { boolean apply = message.optBoolean("apply", true); maintenance.execute(() -> updates.check(apply, false)); return; }
            if (type.equals("diag_batch")) {
                org.json.JSONArray ids = diagnostics.accept(entry, message.getJSONArray("events"));
                send(source, new JSONObject().put("protocol", 1).put("type", "diag_ack").put("entryId", entry).put("ids", ids));
                maintenance.execute(this::uploadDiagnostics); return;
            }
            if (type.equals("diag_status_request")) {
                maintenance.execute(this::uploadDiagnostics);
                send(source, new JSONObject().put("protocol", 1).put("type", "diag_status").put("entryId", entry).put("computerPaired", computer.paired()).put("pending", diagnostics.pending().length()).put("lastUpload", getSharedPreferences("diagnostics", 0).getLong("lastUpload", 0)).put("lastError", getSharedPreferences("diagnostics", 0).getString("lastError", ""))); return;
            }
            if (type.equals("draft_ack")) { String id = message.getString("id"); maintenance.execute(() -> { try { computer.post("/ack", new JSONObject().put("entryId", entry).put("ids", new org.json.JSONArray().put(id))); } catch (Exception ignored) {} }); return; }
            if (type.equals("audio_begin") || type.equals("audio_chunk") || type.equals("audio_end") || type.equals("audio_cancel")) {
                try { forwardAudio(source, bytes, message); } finally { if (type.equals("audio_end") || type.equals("audio_cancel")) recording = false; }
            }
        } catch (Exception error) { state("error", "手环消息处理失败：" + error.getClass().getSimpleName(), 0); }
    }
    private void forwardAudio(String source, byte[] bytes, JSONObject message) throws Exception {
        if (computer != null && computer.paired()) {
            JSONObject result = computer.request("/audio", bytes);
            if (result.optBoolean("complete")) send(source, new JSONObject().put("type", "probe_status").put("entryId", entry).put("audioId", message.getString("audioId")).put("bytes", result.getInt("bytes")));
            return;
        }
        if (token == null || token.isEmpty()) { recording = false; state("error", "开发音频桥尚未配对；未提交任务", 0); return; }
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:8789/audio").openConnection();
        try {
            connection.setConnectTimeout(2500); connection.setReadTimeout(5000); connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token); connection.setRequestProperty("Content-Type", "application/json"); connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            if (connection.getResponseCode() != 200) throw new IOException("audio_receiver_error");
            JSONObject reply = new JSONObject(new String(OtaCoordinator.read(connection.getInputStream(), 8192), StandardCharsets.UTF_8));
            if (reply.optBoolean("complete")) send(source, new JSONObject().put("type", "probe_status").put("entryId", entry).put("audioId", message.getString("audioId")).put("bytes", reply.getInt("bytes")));
        } finally { connection.disconnect(); }
    }
    private void uploadDiagnostics() {
        if (powerprofile != null) return;
        try {
            if (diagnostics == null || computer == null || !computer.paired() || recording) return;
            org.json.JSONArray events = diagnostics.pending(); if (events.length() == 0) return;
            JSONObject result = computer.post("/diagnostics", new JSONObject().put("protocol", 1).put("entryId", entry).put("events", events));
            org.json.JSONArray accepted = result.getJSONArray("accepted");
            // A computer ACK can only retire ids from the batch just sent.
            org.json.JSONArray checked = new org.json.JSONArray();
            for (int i = 0; i < accepted.length(); i++) for (int j = 0; j < events.length(); j++) if (accepted.getString(i).equals(events.getJSONObject(j).getString("id"))) { checked.put(accepted.getString(i)); break; }
            diagnostics.uploaded(checked); getSharedPreferences("diagnostics", 0).edit().putLong("lastUpload", System.currentTimeMillis()).putString("lastError", "").apply();
        } catch (Exception error) { getSharedPreferences("diagnostics", 0).edit().putString("lastError", error.getClass().getSimpleName()).apply(); }
    }
    private void pollComputer() {
        try {
            if (computer == null || !computer.paired() || recording) return;
            if (powerprofile != null) { powerprofile.poll(computer, nodeId); state("synced", "PowerProfile 统计已同步；通知与震动请在小米运动健康核实", 0); return; }
            org.json.JSONArray commands = computer.post("/poll", new JSONObject().put("entryId", entry)).getJSONArray("commands");
            for (int i = 0; i < commands.length(); i++) {
                JSONObject command = commands.getJSONObject(i); String type = command.getString("type");
                if ("update_available".equals(type)) { updates.check(true, false); computer.post("/ack", new JSONObject().put("entryId", entry).put("ids", new org.json.JSONArray().put(command.getString("id")))); }
                else if ("draft_ready".equals(type) && nodeId != null && registered) {
                    send(nodeId, new JSONObject().put("protocol", 1).put("type", "draft_ready").put("entryId", entry).put("id", command.getString("id")).put("audioId", command.getString("audioId")).put("text", command.getString("text")).put("status", "unsubmitted"));
                }
            }
        } catch (Exception ignored) { if (powerprofile != null) state("offline", "电脑未连接，保留上次快照并自动重试", 0); /* Durable commands are not retired. */ }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { if (registered && nodeId != null) Wearable.getMessageApi(this).removeListener(nodeId); work.shutdownNow(); maintenance.shutdownNow(); if (diagnostics != null) diagnostics.close(); super.onDestroy(); }
}
