package io.github.lumishadevoyager.bandagent;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInstaller;

public final class InstallResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (result == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation == null) return;
            PendingIntent action = PendingIntent.getActivity(context, 23, confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(context, BridgeService.CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Band Agent 手机更新待确认").setContentText("点击完成 Android 系统安装确认").setContentIntent(action).setAutoCancel(true).build();
            context.getSystemService(NotificationManager.class).notify(23, notification);
            context.getSharedPreferences("ota", 0).edit().putString("lastStatus", "手机更新已下载校验，请点击通知确认安装").apply();
        } else {
            context.getSharedPreferences("ota", 0).edit().remove("apkPendingCode").remove("apkSession").putString("lastStatus", result == PackageInstaller.STATUS_SUCCESS ? "手机更新完成" : "手机更新未完成，状态码 " + result).apply();
        }
    }
}
