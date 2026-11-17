package io.github.lumishadevoyager.bandagent;

import android.app.PendingIntent;
import android.content.*;
import android.content.pm.*;
import java.io.*;
import org.json.JSONObject;

public final class ApkUpdateInstaller {
    public static long current(Context context) throws Exception { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode(); }
    public static void install(Context context, File file, JSONObject item) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null || !context.getPackageName().equals(archive.packageName) || archive.getLongVersionCode() != item.getLong("versionCode") || archive.getLongVersionCode() <= installed.getLongVersionCode()) throw new SecurityException("apk_identity_version_mismatch");
        android.content.pm.Signature[] oldSigners = installed.signingInfo.getApkContentsSigners(), newSigners = archive.signingInfo.getApkContentsSigners();
        if (oldSigners.length != 1 || newSigners.length != 1 || !oldSigners[0].equals(newSigners[0]) || !SignedFeed.sha256(newSigners[0].toByteArray()).equals(item.getString("signerSha256"))) throw new SecurityException("apk_signer_mismatch");
        if (!pm.canRequestPackageInstalls()) throw new SecurityException("allow_unknown_app_installation_required");
        PackageInstaller installer = pm.getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName()); params.setSize(file.length());
        if (android.os.Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(file); OutputStream out = session.openWrite("base.apk", 0, file.length())) {
                byte[] bytes = new byte[16384]; int count; while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count); session.fsync(out);
            }
            Intent result = new Intent(context, InstallResultReceiver.class).setAction(context.getPackageName() + ".INSTALL_RESULT");
            PendingIntent pending = PendingIntent.getBroadcast(context, id, result, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            context.getSharedPreferences("ota", 0).edit().putLong("apkPendingCode", item.getLong("versionCode")).putInt("apkSession", id).commit();
            session.commit(pending.getIntentSender()); committed = true;
        } finally { if (!committed) installer.abandonSession(id); }
    }
}
