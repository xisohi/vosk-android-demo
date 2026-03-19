package org.vosk.demo;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ToggleButton;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import org.vosk.demo.service.VoiceAssistantService;

import java.io.IOException;

public class VoskActivity extends AppCompatActivity {

    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    private static final int REQUEST_OVERLAY_PERMISSION = 2;

    private Button recognizeFileButton;
    private Button recognizeMicButton;
    private ToggleButton pauseButton;
    private TextView resultText;
    private static final String MODEL_ASSET_PATH = "vosk-model-small-cn-0.22";
    private static final String MODEL_INTERNAL_DIR = "models"; // 内部存储中的目录名
    // 广播接收器：接收服务发来的权限请求
    private BroadcastReceiver permissionRequestReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("org.vosk.demo.REQUEST_PERMISSION".equals(intent.getAction())) {
                String permission = intent.getStringExtra("permission");
                if (Manifest.permission.RECORD_AUDIO.equals(permission)) {
                    // 请求录音权限
                    ActivityCompat.requestPermissions(VoskActivity.this,
                            new String[]{Manifest.permission.RECORD_AUDIO},
                            PERMISSIONS_REQUEST_RECORD_AUDIO);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main); // 你的布局文件名是 main.xml

        // 初始化视图
        recognizeFileButton = findViewById(R.id.recognize_file);
        recognizeMicButton = findViewById(R.id.recognize_mic);
        pauseButton = findViewById(R.id.pause);
        resultText = findViewById(R.id.result_text);

        // 检查并请求权限
        checkPermissions();

        // 启动语音助手服务
        startVoiceService();

        // 注册广播接收器（接收服务发来的权限请求）
        IntentFilter filter = new IntentFilter("org.vosk.demo.REQUEST_PERMISSION");
        ContextCompat.registerReceiver(this, permissionRequestReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        // 保留原有按钮逻辑（你需要根据自己的原有代码补充）
        // 例如：
        recognizeFileButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 原有识别文件的代码
                Toast.makeText(VoskActivity.this, "识别文件（需实现）", Toast.LENGTH_SHORT).show();
            }
        });

        recognizeMicButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 原有识别麦克风的代码
                Toast.makeText(VoskActivity.this, "识别麦克风（需实现）", Toast.LENGTH_SHORT).show();
            }
        });

        pauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 原有暂停逻辑
            }
        });
    }

    /**
     * 检查并请求权限：录音权限和悬浮窗权限
     */
    private void checkPermissions() {
        // 录音权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSIONS_REQUEST_RECORD_AUDIO);
        }

        // 悬浮窗权限（Android 6.0+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
            }
        }
    }

    /**
     * 启动语音助手服务
     */
    private void startVoiceService() {
        Intent serviceIntent = new Intent(this, VoiceAssistantService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "录音权限已授予", Toast.LENGTH_SHORT).show();
                // 权限授予后，可以重新尝试启动唤醒词监听（如果服务已运行，它会自动处理）
            } else {
                Toast.makeText(this, "录音权限被拒绝，部分功能无法使用", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show();
                    // 可以重新尝试显示悬浮球（服务会自动处理）
                } else {
                    Toast.makeText(this, "悬浮窗权限被拒绝，悬浮球无法显示", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(permissionRequestReceiver);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}