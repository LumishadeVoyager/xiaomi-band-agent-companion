package io.github.lumishadevoyager.bandagent;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.ClipData;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Base64;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.CheckBox;
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
    public static volatile boolean visible;
    private TextView status;
    private PowerProfilePhoneUi phoneUi;
    private Node node;
    private boolean registered;
    private String entry;
    private String token;
    private String pendingText;
    private boolean development;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { show(intent.getStringExtra("text")); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            JSONObject config = new JSONObject(read(getAssets().open("development.json")));
            entry = config.getString("entryId");
            token = config.getString("token");
            development = config.optBoolean("development", false);
        } catch (Exception error) { throw new IllegalStateException("Missing development configuration", error); }
        if (entry.equals("powerprofile")) {
            getWindow().setStatusBarColor(android.graphics.Color.rgb(10,14,20));getWindow().setNavigationBarColor(android.graphics.Color.rgb(10,14,20));
            phoneUi=new PowerProfilePhoneUi(this);setContentView(phoneUi.create(this::connect,this::launchWearApp,this::installWearApp));
            // Cloud sync and control start even when the band is not nearby.
            try{startForegroundService(new Intent(this,BridgeService.class));}catch(RuntimeException ignored){phoneUi.note("后台服务启动失败，请检查应用权限");}
        } else {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 80, 48, 48);
        status = new TextView(this); status.setTextSize(20);
        if (entry.equals("powerprofile")) { status.setMinLines(4); status.setMaxLines(4); status.setEllipsize(android.text.TextUtils.TruncateAt.END); }
        layout.addView(status);
        button(layout, "连接手环并授权", this::connect);
        button(layout, "打开对应手环应用", this::launchWearApp);
        button(layout, "用 AstroBox 安装对应测试包", this::installWearApp);
        button(layout, "检查并安装更新", () -> startForegroundService(new Intent(this, BridgeService.class).setAction("check")));
        if (entry.equals("powerprofile")) {
            button(layout, "配置手环更新安装通路", () -> startForegroundService(new Intent(this, BridgeService.class).setAction("installer_setup")));
            button(layout, "通知设置", () -> startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())));
            button(layout, "10 秒后发送测试提醒", () -> { startForegroundService(new Intent(this, BridgeService.class).setAction("powerprofile_test_notice")); show("10 秒后发送明确标记的测试通知；如运动健康仅锁屏通知，请锁屏并佩戴手环"); });
            TextView hint = new TextView(this); hint.setText("在小米运动健康的 App 通知中启用本应用；手环震动受系统勿扰设置控制。监看每 15 秒同步，额度每分钟读取。后台每 15 分钟检查预览更新；手机安装可能需要系统确认。"); layout.addView(hint);
        }
        button(layout, "打开 AstroBox（首次允许安装通路）", () -> { Intent launch = getPackageManager().getLaunchIntentForPackage("moe.astralsight.astrobox"); if (launch != null) startActivity(launch); });
        button(layout, "允许本应用更新手机安装包", () -> startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))));
        CheckBox automatic = new CheckBox(this); automatic.setText("自动更新 · preview 测试通道"); automatic.setChecked(getSharedPreferences("ota", 0).getBoolean("autoUpdates", true));
        automatic.setOnCheckedChangeListener((button, checked) -> getSharedPreferences("ota", 0).edit().putBoolean("autoUpdates", checked).apply()); layout.addView(automatic);
        setContentView(layout);
        }
        registerReceiver(statusReceiver, new IntentFilter(getPackageName() + ".STATUS"), Context.RECEIVER_NOT_EXPORTED);
        show((entry.equals("powerprofile") ? "PowerProfile" : entry.equals("codex") ? "Codex" : "DeepSeek Harness") + " 开发预览\n" + getSharedPreferences("ota", 0).getString("lastStatus", "等待连接与配对"));
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 10);
        connect();
        acceptDevelopmentIntent(getIntent());
        acceptComputerPairing(getIntent());
    }
    private void button(LinearLayout layout, String title, Runnable action) {
        Button button = new Button(this); button.setText(title); button.setOnClickListener(v -> action.run()); layout.addView(button);
    }
    private void show(String text) { runOnUiThread(() -> {if(phoneUi!=null)phoneUi.note(text);else status.setText(text);}); }
    @Override protected void onResume(){super.onResume();visible=true;if(phoneUi!=null)phoneUi.start();}
    @Override protected void onPause(){visible=false;if(phoneUi!=null)phoneUi.stop();super.onPause();}
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
        try { startForegroundService(new Intent(this, BridgeService.class).putExtra("nodeId", node.id)); registered = true; show("后台服务已启动，请在手环打开应用"); sendPendingText(); }
        catch (Exception error) { show("后台服务启动失败：" + error.getClass().getSimpleName()); }
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
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); acceptDevelopmentIntent(intent); acceptComputerPairing(intent); }
    private void acceptComputerPairing(Intent intent) {
        String encoded = intent.getStringExtra("signed_pairing_b64"); if (encoded == null || encoded.length() > 8192) return;
        try { ComputerLink link = new ComputerLink(this); link.pair(Base64.decode(encoded, Base64.DEFAULT)); startForegroundService(new Intent(this, BridgeService.class).setAction("reload_link")); show(entry.equals("powerprofile") ? (link.internet() ? "互联网配对已保存，可使用移动数据同步" : "电脑签名配对已保存，开始读取统计") : "电脑签名配对已保存，日志与音频将自动上传"); }
        catch (Exception error) { show("电脑配对未通过校验：" + error.getClass().getSimpleName()); }
    }
    private void acceptDevelopmentIntent(Intent intent) {
        if (!development) return;
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
        unregisterReceiver(statusReceiver); super.onDestroy();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) { super.onRequestPermissionsResult(code, permissions, results); }
}
