package io.github.lumishadevoyager.bandagent;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.ClipData;
import android.net.Uri;
import android.util.Base64;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.auth.Permission;
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener;
import com.xiaomi.xms.wearable.node.Node;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Development-only bridge: no phone microphone, cloud calls, or Agent execution. */
public final class BridgeActivity extends Activity {
    private TextView status;
    private Node node;
    private boolean registered;
    private String entry;
    private String token;
    private final ExecutorService transfer = Executors.newSingleThreadExecutor();
    private final OnMessageReceivedListener listener = (nodeId, bytes) -> {
        final String sourceNode = nodeId;
        transfer.execute(() -> forward(sourceNode, bytes));
    };
    private String pendingText;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            JSONObject config = new JSONObject(read(getAssets().open("development.json")));
            entry = config.getString("entryId");
            token = config.getString("token");
        } catch (Exception error) { throw new IllegalStateException("Missing development configuration", error); }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 80, 48, 48);
        status = new TextView(this); status.setTextSize(20);
        layout.addView(status);
        button(layout, "连接手环并授权", this::connect);
        button(layout, "打开对应手环应用", this::launchWearApp);
        button(layout, "用 AstroBox 安装对应测试包", this::installWearApp);
        setContentView(layout);
        show((entry.equals("codex") ? "Codex" : "DeepSeek Harness") + " 录音测试伴侣\n不采集手机麦克风，不执行任务");
        connect();
        acceptDevelopmentIntent(getIntent());
    }
    private void button(LinearLayout layout, String title, Runnable action) {
        Button button = new Button(this); button.setText(title); button.setOnClickListener(v -> action.run()); layout.addView(button);
    }
    private void show(String text) { runOnUiThread(() -> status.setText(text)); }
    private void connect() {
        Wearable.getNodeApi(this).getConnectedNodes().addOnSuccessListener(nodes -> {
            if (nodes.isEmpty()) { show("小米运动健康未返回已连接设备"); return; }
            node = nodes.get(0);
            Wearable.getAuthApi(this).requestPermission(node.id, Permission.DEVICE_MANAGER, Permission.NOTIFY)
                .addOnSuccessListener(permissions -> register())
                .addOnFailureListener(error -> show("手环授权失败：" + error.getClass().getSimpleName()));
        }).addOnFailureListener(error -> show("设备查询失败：" + error.getClass().getSimpleName()));
    }
    private void register() {
        if (registered) { show("监听已就绪，请在手环打开对应应用"); return; }
        Wearable.getMessageApi(this).addListener(node.id, listener)
            .addOnSuccessListener(ignored -> { registered = true; show("监听已就绪，请在手环录音\n电脑需运行 USB 音频接收器"); sendPendingText(); })
            .addOnFailureListener(error -> show("消息监听失败：" + error.getClass().getSimpleName()));
    }
    private void launchWearApp() {
        if (node == null) { connect(); return; }
        Wearable.getNodeApi(this).launchWearApp(node.id, "/pages/index")
            .addOnSuccessListener(ignored -> show("已请求打开手环应用"))
            .addOnFailureListener(error -> show("手环应用打开失败：" + error.getClass().getSimpleName()));
    }
    private void installWearApp() {
        try {
            Uri uri = Uri.parse("content://" + getPackageName() + ".probe-files/wearable.rpk");
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/octet-stream");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.setClipData(ClipData.newRawUri("wearable.rpk", uri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setPackage("moe.astralsight.astrobox");
            startActivity(share);
        } catch (Exception error) { show("无法打开 AstroBox：" + error.getClass().getSimpleName()); }
    }
    private void forward(String nodeId, byte[] bytes) {
        HttpURLConnection connection = null;
        try {
            if (bytes.length > 8192) throw new IllegalArgumentException("message_too_large");
            JSONObject message = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!entry.equals(message.optString("entryId")) || message.optInt("protocol") != 1) return;
            String type = message.optString("type");
            if (type.equals("probe_capabilities")) { String version = message.optString("version", "unknown"); show("手环接口诊断 " + version + "：\n" + message.optJSONObject("record")); android.util.Log.i("BandAgentProbe", "version=" + version + " capabilities=" + message.optJSONObject("record")); return; }
            if (!(type.equals("audio_begin") || type.equals("audio_chunk") || type.equals("audio_end") || type.equals("audio_cancel"))) return;
            connection = (HttpURLConnection) new URL("http://127.0.0.1:8789/audio").openConnection();
            connection.setConnectTimeout(2500); connection.setReadTimeout(5000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int code = connection.getResponseCode();
            JSONObject reply = new JSONObject(read(code == 200 ? connection.getInputStream() : connection.getErrorStream()));
            if (code != 200) throw new IllegalStateException(reply.optString("error", "receiver_error"));
            if (reply.optBoolean("complete")) {
                show("电脑已收到手环音频：" + reply.optInt("bytes") + " 字节\n尚未转写或执行");
                JSONObject statusMessage = new JSONObject().put("type", "probe_status").put("audioId", message.getString("audioId")).put("entryId", entry).put("text", "电脑已收到音频").put("bytes", reply.optInt("bytes"));
                Wearable.getMessageApi(this).sendMessage(nodeId, statusMessage.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) { show("USB 音频转发失败：" + error.getClass().getSimpleName()); }
        finally { if (connection != null) connection.disconnect(); }
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); acceptDevelopmentIntent(intent); }
    private void acceptDevelopmentIntent(Intent intent) {
        String encoded = intent.getStringExtra("transcript_b64");
        if (encoded == null || encoded.length() > 2048) return;
        try { pendingText = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8); sendPendingText(); }
        catch (Exception error) { show("测试文本格式错误"); }
    }
    private void sendPendingText() {
        if (!registered || node == null || pendingText == null) return;
        try {
            JSONObject message = new JSONObject().put("type", "probe_text").put("entryId", entry).put("text", pendingText);
            pendingText = null;
            Wearable.getMessageApi(this).sendMessage(node.id, message.toString().getBytes(StandardCharsets.UTF_8))
                .addOnSuccessListener(ignored -> show("已发送测试转写到手环；未启动任何 Agent 任务"))
                .addOnFailureListener(error -> show("手环文本发送失败：" + error.getClass().getSimpleName()));
        } catch (Exception error) { show("测试文本发送错误"); }
    }
    private static String read(InputStream input) throws java.io.IOException {
        if (input == null) throw new java.io.IOException("empty_response");
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) { out.write(buffer, 0, count); if (out.size() > 8192) throw new java.io.IOException("response_too_large"); }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    @Override public void onDestroy() {
        if (registered && node != null) Wearable.getMessageApi(this).removeListener(node.id);
        transfer.shutdownNow(); super.onDestroy();
    }
}
