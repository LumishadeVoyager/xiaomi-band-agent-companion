package io.github.lumishadevoyager.bandagent;

import android.content.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.zip.ZipFile;
import org.json.*;

public final class OtaCoordinator {
    public interface Sink { void state(String phase, String text, int progress); boolean busy(); }
    private final Context context;
    private final String entry;
    private final Sink sink;
    private final SharedPreferences prefs;
    private final PublicKey root;
    private AstroBoxApi astro;
    private long lastProgress;
    private long lastSetupAttempt;
    public OtaCoordinator(Context context, String entry, Sink sink) throws Exception {
        this.context = context; this.entry = entry; this.sink = sink; prefs = context.getSharedPreferences("ota", 0);
        root = SignedFeed.publicKey(new String(read(context.getAssets().open("update-public.pem"), 8192), StandardCharsets.US_ASCII));
    }
    public static byte[] read(InputStream input, int max) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] bytes = new byte[8192]; int count; while ((count = in.read(bytes)) != -1) { if (out.size() + count > max) throw new IOException("input_too_large"); out.write(bytes, 0, count); } return out.toByteArray(); }
    }
    public void hello(long actualCode) {
        prefs.edit().putLong("wearActualCode", actualCode).apply();
        long pending = prefs.getLong("wearPendingCode", 0);
        if (pending > 0 && actualCode >= pending) { prefs.edit().remove("wearPendingCode").remove("wearTaskId").apply(); sink.state("verified", "手环更新完成，实际版本码 " + actualCode, 100); }
    }
    private AstroBoxApi astro() throws Exception { if (astro == null) astro = new AstroBoxApi(context); return astro; }
    public void prepareInstaller() {
        lastSetupAttempt = android.os.SystemClock.elapsedRealtime();
        try { device(); prefs.edit().putBoolean("installerSetupPending", false).apply(); sink.state("installer_ready", "手环更新安装通路已授权", 100); }
        catch (Exception error) {
            if ("astrobox_pairing_required".equals(error.getMessage())) { prefs.edit().putBoolean("installerSetupPending", true).apply(); sink.state("installer_setup", "请在 AstroBox 同意 PowerProfile 安装授权，然后重新检查更新", 0); }
            else { String code = error.getMessage(); if (code == null || !code.matches("[a-z_0-9]{1,80}")) code = error.getClass().getSimpleName(); sink.state("installer_setup", "安装通路未就绪：" + code + "；请打开 AstroBox 后重试", 0); }
        }
    }
    private String device() throws Exception {
        String saved = prefs.getString("deviceId", ""); JSONArray devices = astro().devices(); String candidate = null; int connected = 0;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.getJSONObject(i);
            if (!"connected".equals(device.getString("connectionState"))) continue;
            if (!saved.isEmpty() && saved.equals(device.getString("deviceId"))) return saved;
            candidate = device.getString("deviceId"); connected++;
        }
        if (!saved.isEmpty()) { sink.state("connecting_installer", "正在连接手环安装通路", 0); astro().connectKnown(saved); return saved; }
        if (connected == 0 && devices.length() == 1 && devices.getJSONObject(0).optBoolean("known") && devices.getJSONObject(0).getString("name").contains("10 Pro")) candidate = devices.getJSONObject(0).getString("deviceId");
        else if (connected != 1) throw new IOException("astrobox_select_one_connected_device");
        prefs.edit().putString("deviceId", candidate).commit(); if (connected == 0) astro().connectKnown(candidate); return candidate;
    }
    public void check(boolean apply, boolean automatic) {
        try {
            if (sink.busy()) { sink.state("deferred", "录音处理中，更新已延期", 0); return; }
            if (prefs.getBoolean("autoUpdates", true) == false && automatic) return;
            if (!prefs.getString("wearTaskId", "").isEmpty()) { poll(); return; }
            long now = System.currentTimeMillis() / 1000;
            String channel = prefs.getString("channel", "preview");
            if (!channel.equals("preview") && !channel.equals("stable")) throw new SecurityException("invalid_channel");
            sink.state("checking", "正在检查 " + channel + " 更新", 0);
            String feedUrl = entry.equals("powerprofile") ? SignedFeed.OTA_ORIGIN + "/ota/powerprofile-" + channel + ".json" : "https://raw.githubusercontent.com/" + SignedFeed.REPOSITORY + "/main/updates/" + channel + ".json";
            byte[] bytes = OtaHttp.bytes(feedUrl, 131072);
            SignedFeed feed = SignedFeed.verify(bytes, root, channel, now);
            long phoneCode = ApkUpdateInstaller.current(context);
            String model = prefs.getString("model", "M2551B1"), region = prefs.getString("region", "CN");
            JSONObject phone = feed.component(entry, "android", model, region, phoneCode, prefs.getLong("highestAndroid", 0));
            JSONObject wearable = feed.component(entry, "wearable", model, region, prefs.getLong("wearActualCode", 0), prefs.getLong("highestWearable", 0));
            if (phone == null && wearable == null) { sink.state("current", "手机与手环均已是最新版本", 100); return; }
            if (!apply) { sink.state("available", "发现更新，点击更新以安装", 0); return; }
            if (phone != null) {
                if (prefs.getLong("apkPendingCode", 0) > phoneCode && context.getPackageManager().getPackageInstaller().getSessionInfo(prefs.getInt("apkSession", -1)) == null) prefs.edit().remove("apkPendingCode").remove("apkSession").commit();
                if (prefs.getLong("apkPendingCode", 0) > phoneCode) { sink.state("phone_confirmation", "手机更新待系统确认，请查看手机通知", 0); return; }
                File apk = download(phone);
                if (sink.busy()) { sink.state("deferred", "录音处理中，安装已延期", 0); return; }
                ApkUpdateInstaller.install(context, apk, phone);
                prefs.edit().putLong("highestAndroid", phone.getLong("versionCode")).commit();
                sink.state("phone_confirmation", "手机更新已校验，等待系统安装确认", 100); return;
            }
            if (wearable != null) {
                if (wearable.getLong("bridgeMinCode") > phoneCode) throw new SecurityException("phone_update_required_first");
                String target = device(); prefs.edit().putBoolean("installerSetupPending", false).apply(); long actual = astro().installedVersion(target, context.getPackageName());
                if (actual >= wearable.getLong("versionCode")) { hello(actual); sink.state("current", "已核对手环实际版本，无需更新", 100); return; }
                File rpk = download(wearable); verifyRpk(rpk, wearable);
                if (sink.busy()) { sink.state("deferred", "录音处理中，安装已延期", 0); return; }
                // Persist intent before enqueue: if the host dies, query actual
                // version before permitting another installation attempt.
                prefs.edit().putLong("wearPendingCode", wearable.getLong("versionCode")).putLong("highestWearable", wearable.getLong("versionCode")).putString("wearTaskId", "result_unknown").commit();
                String id = astro().install(target, rpk);
                prefs.edit().putString("wearTaskId", id).commit(); astro().startQueue(target);
                sink.state("installing", "手环安装已入队，正在读取结果", 0);
            }
        } catch (Exception error) {
            android.util.Log.e("BandAgentBridge", "update failed", error);
            String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            if (reason.equals("http_404")) sink.state("no_release", "当前通道尚未发布可安装更新", 0);
            else if (reason.equals("allow_unknown_app_installation_required")) sink.state("phone_permission", "请在手机伴侣允许本应用安装更新，然后重新检查更新", 0);
            else if (reason.equals("astrobox_pairing_required")) { prefs.edit().putBoolean("installerSetupPending", true).apply(); sink.state("installer_setup", "首次更新请在 AstroBox 允许 Band Agent 安装通路", 0); }
            else sink.state("error", reason, 0);
        }
    }
    public void poll() {
        String taskId = prefs.getString("wearTaskId", ""); if (taskId.isEmpty()) { if (prefs.getBoolean("installerSetupPending", false) && android.os.SystemClock.elapsedRealtime() - lastSetupAttempt > 60000) prepareInstaller(); return; }
        try {
            String target = prefs.getString("deviceId", ""); long expected = prefs.getLong("wearPendingCode", 0);
            long actual = astro().installedVersion(target, context.getPackageName());
            if (actual >= expected && expected > 0) { hello(actual); astro().disconnect(target); return; }
            if ("result_unknown".equals(taskId)) { sink.state("unknown", "安装结果待核对，未重复安装；请保持 AstroBox 连接", 0); return; }
            JSONObject task = astro().task(taskId); String state = task.getString("status");
            if (state.equals("failed") || state.equals("canceled")) { prefs.edit().remove("wearTaskId").remove("wearPendingCode").commit(); sink.state("failed", "手环安装" + state + "：" + task.optString("errorCode"), 0); return; }
            sink.state("installing", state.equals("succeeded") ? "安装通道完成，等待实际版本确认" : "手环更新 " + state, (int) (task.optDouble("progress", 0) * 100));
        } catch (Exception error) { sink.state("unknown", "等待 AstroBox 恢复连接并核对实际版本", 0); }
    }
    private File download(JSONObject item) throws Exception {
        sink.state("downloading", "正在下载并校验 " + item.getString("kind"), 0);
        return OtaHttp.artifact(new File(context.getFilesDir(), "updates"), item, (done, total) -> { if (System.currentTimeMillis() - lastProgress > 1000) { lastProgress = System.currentTimeMillis(); sink.state("downloading", "正在下载更新", (int) (done * 100 / total)); } });
    }
    private void verifyRpk(File file, JSONObject item) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            JSONObject manifest = new JSONObject(new String(read(zip.getInputStream(zip.getEntry("manifest.json")), 32768), StandardCharsets.UTF_8));
            if (!context.getPackageName().equals(manifest.getString("package")) || manifest.getLong("versionCode") != item.getLong("versionCode")) throw new SecurityException("rpk_identity_version_mismatch");
        }
    }
}
