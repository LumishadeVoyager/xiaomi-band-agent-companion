package io.github.lumishadevoyager.bandagent;

import android.app.*;
import android.app.job.*;
import android.content.*;

/** System-owned, persisted recovery. Never loops or bypasses a force-stop. */
public final class BridgeRecovery extends JobService {
    static final int JOB = 1701;
    static void schedule(Context context) {
        context.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(JOB,
            new ComponentName(context, BridgeRecovery.class)).setPersisted(true)
            .setPeriodic(15 * 60 * 1000L).build());
    }
    static void recover(Context context) {
        if (BridgeService.running) return;
        try { context.startForegroundService(new Intent(context, BridgeService.class)); }
        catch (RuntimeException denied) {
            context.getSharedPreferences("runtime",0).edit().putString("recoveryError",denied.getClass().getSimpleName()).apply();
            NotificationManager manager=context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(BridgeService.CHANNEL,"手环连接与更新",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(context,17,new Intent(context,BridgeActivity.class),PendingIntent.FLAG_IMMUTABLE);
            try { manager.notify(17,new Notification.Builder(context,BridgeService.CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("PowerProfile 连接已暂停")
                .setContentText("系统限制了后台启动，点此恢复同步").setContentIntent(open).setAutoCancel(true).build()); }
            catch (SecurityException ignored) { /* The next user launch exposes the failure. */ }
        }
    }
    @Override public boolean onStartJob(JobParameters parameters) { recover(this); return false; }
    @Override public boolean onStopJob(JobParameters parameters) { return true; }
}
