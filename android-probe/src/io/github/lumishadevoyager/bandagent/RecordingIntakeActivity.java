package io.github.lumishadevoyager.bandagent;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Imports the recording URI granted by Mi Fitness; never opens the phone mic. */
public final class RecordingIntakeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this); text.setTextSize(22); text.setPadding(48, 100, 48, 48); text.setText("正在上传手环录音…"); setContentView(text);
        Uri audio = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        new Thread(() -> {
            try {
                if (audio == null) throw new IllegalArgumentException("未收到录音文件");
                JSONObject config = new JSONObject(new String(OtaCoordinator.read(getAssets().open("development.json"), 8192), StandardCharsets.UTF_8));
                String entry = config.getString("entryId"), id = "audio_" + Long.toString(System.currentTimeMillis(), 36);
                byte[] bytes;
                try (InputStream in = getContentResolver().openInputStream(audio)) { bytes = OtaCoordinator.read(in, 2 * 1024 * 1024); }
                new ComputerLink(this).request("/recording?entryId=" + entry + "&audioId=" + id, bytes);
                runOnUiThread(() -> text.setText("手环录音已上传，电脑正在转写。\n返回手环 Codex/Harness 应用查看草稿。\n尚未提交 Agent 任务。"));
            } catch (Exception error) { runOnUiThread(() -> text.setText("录音上传失败：" + error.getMessage())); }
        }, "band-recording-upload").start();
    }
}
