package io.github.lumishadevoyager.bandagent;
import android.content.*;
public final class RestartReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()) || Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            BridgeRecovery.schedule(context); BridgeRecovery.recover(context);
        }
    }
}
