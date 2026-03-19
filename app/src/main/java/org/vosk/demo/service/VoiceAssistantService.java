package org.vosk.demo.service;

import android.app.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.demo.R;
import org.vosk.demo.VoskActivity;
import org.vosk.demo.ui.FloatWindowManager;
import org.vosk.demo.utils.AssetCopyUtil;  // 新增导入

import java.io.File;
import java.io.IOException;

public class VoiceAssistantService extends Service {

    private static final String TAG = "VoiceAssistantService";
    private static final String CHANNEL_ID = "voice_assistant_channel";
    private static final int NOTIFICATION_ID = 1001;

    // 模型相关常量
    private static final String MODEL_ASSET_PATH = "vosk-model-small-cn-0.22";
    private static final String MODEL_INTERNAL_DIR = "models";

    // 组件
    private FloatWindowManager floatWindowManager;
    private WakeWordService wakeWordService;

    // Vosk 相关
    private Model model;
    private SpeechService speechService;
    private Recognizer recognizer;

    // 状态
    private boolean isListening = false;
    private boolean isWakeWordEnabled = true;

    // 广播接收器
    private BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("org.vosk.demo.START_LISTENING".equals(action)) {
                startVoiceRecognition();
            } else if ("org.vosk.demo.STOP_LISTENING".equals(action)) {
                stopVoiceRecognition();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "服务创建");

        // 创建通知渠道（Android 8.0+）
        createNotificationChannel();

        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification());

        // 初始化悬浮窗管理器
        floatWindowManager = new FloatWindowManager(this);

        // 初始化Vosk（现在包含模型解压逻辑）
        initVosk();

        // 初始化唤醒词检测
        initWakeWordDetection();

        // 注册广播接收器（适配 Android 12+）
        IntentFilter filter = new IntentFilter();
        filter.addAction("org.vosk.demo.START_LISTENING");
        filter.addAction("org.vosk.demo.STOP_LISTENING");
        ContextCompat.registerReceiver(this, commandReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        // 显示悬浮球
        floatWindowManager.showFloatBall();

        // 如果唤醒词功能开启并且有录音权限，开始监听
        if (isWakeWordEnabled && wakeWordService != null) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                wakeWordService.startListening();
            } else {
                Log.w(TAG, "没有录音权限，唤醒词监听未启动");
                floatWindowManager.showTip("需要录音权限才能使用唤醒词");
            }
        }

        Toast.makeText(this, "语音助手已启动", Toast.LENGTH_SHORT).show();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "语音助手服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持语音助手后台运行");
            channel.setSound(null, null);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent intent = new Intent(this, VoskActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("语音助手")
                .setContentText("正在后台运行，点击打开")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    // ==================== 模型初始化与加载 ====================
    private void initVosk() {
        Log.d(TAG, "初始化Vosk");
        LibVosk.setLogLevel(LogLevel.INFO);

        // 模型目标路径：/data/data/org.vosk.demo/files/models/vosk-model-small-cn-0.22/
        File modelDir = new File(getFilesDir(), MODEL_INTERNAL_DIR + "/" + MODEL_ASSET_PATH);

        if (modelDir.exists()) {
            // 模型已存在，直接加载
            loadModel(modelDir.getAbsolutePath());
        } else {
            // 模型不存在，需要从 assets 解压
            Toast.makeText(this, "首次使用，正在解压语音模型（约40MB），请稍候...", Toast.LENGTH_LONG).show();

            File destDir = new File(getFilesDir(), MODEL_INTERNAL_DIR);
            AssetCopyUtil.copyAssetFolderToInternalStorage(this, MODEL_ASSET_PATH, destDir,
                    new AssetCopyUtil.CopyListener() {
                        @Override
                        public void onStart() {
                            Log.d(TAG, "开始解压模型");
                        }

                        @Override
                        public void onProgress(int percent) {
                            Log.d(TAG, "解压进度: " + percent + "%");
                        }

                        @Override
                        public void onSuccess(File dir) {
                            Log.d(TAG, "模型解压成功，路径: " + dir.getAbsolutePath());
                            runOnUiThread(() -> {
                                Toast.makeText(VoiceAssistantService.this, "模型解压完成", Toast.LENGTH_SHORT).show();
                            });
                            // 解压完成后加载模型
                            loadModel(new File(dir, MODEL_ASSET_PATH).getAbsolutePath());
                        }

                        @Override
                        public void onError() {
                            Log.e(TAG, "模型解压失败");
                            runOnUiThread(() -> {
                                Toast.makeText(VoiceAssistantService.this, "模型解压失败，请检查存储空间", Toast.LENGTH_LONG).show();
                            });
                        }
                    });
        }
    }

    private void loadModel(String path) {
        try {
            model = new Model(path);
            Log.d(TAG, "模型加载成功: " + path);
        } catch (Exception e) {
            Log.e(TAG, "模型加载失败: " + e.getMessage());
        }
    }
    // ========================================================

    private void initWakeWordDetection() {
        wakeWordService = new WakeWordService();
        wakeWordService.setWakeWordListener(new WakeWordService.WakeWordListener() {
            @Override
            public void onWakeWordDetected() {
                Log.d(TAG, "唤醒词检测到");

                // 在主线程中处理
                VoiceAssistantService.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (floatWindowManager != null) {
                            floatWindowManager.showTip("我在");
                        }
                        startVoiceRecognition();
                    }
                });
            }
        });
    }

    private void runOnUiThread(Runnable runnable) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(runnable);
    }

    private void startVoiceRecognition() {
        // 检查录音权限
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "缺少录音权限，无法启动语音识别");
            if (floatWindowManager != null) {
                floatWindowManager.showTip("请在设置中授予录音权限");
            }
            Intent intent = new Intent("org.vosk.demo.REQUEST_PERMISSION");
            intent.putExtra("permission", android.Manifest.permission.RECORD_AUDIO);
            sendBroadcast(intent);
            return;
        }

        if (isListening) return;

        if (model == null) {
            Log.e(TAG, "模型未初始化");
            floatWindowManager.showTip("模型未加载");
            return;
        }

        try {
            recognizer = new Recognizer(model, 16000.0f);
            speechService = new SpeechService(recognizer, 16000.0f);
            speechService.startListening(new RecognitionListener() {
                @Override
                public void onResult(String hypothesis) {
                    Log.d(TAG, "识别结果: " + hypothesis);
                    processVoiceCommand(hypothesis);
                }

                @Override
                public void onFinalResult(String hypothesis) {
                    Log.d(TAG, "最终结果: " + hypothesis);
                    processVoiceCommand(hypothesis);
                }

                @Override
                public void onPartialResult(String hypothesis) {
                    // 部分结果，可忽略或用于实时显示
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "识别错误: " + e.getMessage());
                    floatWindowManager.showTip("识别错误");
                    isListening = false;
                    floatWindowManager.setListeningState(false);
                }

                @Override
                public void onTimeout() {
                    Log.d(TAG, "识别超时");
                    stopVoiceRecognition();
                }
            });

            isListening = true;
            floatWindowManager.setListeningState(true);
            floatWindowManager.showTip("请说话");

            Log.d(TAG, "语音识别已启动");

        } catch (IOException e) {
            Log.e(TAG, "启动识别失败: " + e.getMessage());
            floatWindowManager.showTip("启动失败");
        }
    }

    private void stopVoiceRecognition() {
        if (speechService != null) {
            speechService.stop();
            speechService = null;
        }

        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }

        isListening = false;
        floatWindowManager.setListeningState(false);

        Log.d(TAG, "语音识别已停止");

        // 如果唤醒词功能开启，重新开始监听唤醒词
        if (isWakeWordEnabled && wakeWordService != null && !wakeWordService.isListening()) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                wakeWordService.startListening();
            }
        }
    }

    private void processVoiceCommand(String jsonResult) {
        Log.d(TAG, "处理命令: " + jsonResult);

        String text = extractTextFromJson(jsonResult);
        if (text == null || text.isEmpty()) {
            return;
        }

        text = text.toLowerCase();
        Log.d(TAG, "识别文本: " + text);

        if (text.contains("导航") || text.contains("去")) {
            String destination = text.replace("导航", "").replace("去", "").trim();
            openNavigation(destination);

        } else if (text.contains("播放音乐") || text.contains("放歌")) {
            controlMusic("play");

        } else if (text.contains("暂停") || text.contains("停止")) {
            controlMusic("pause");

        } else if (text.contains("下一首")) {
            controlMusic("next");

        } else if (text.contains("音量")) {
            String level = text.replaceAll("[^0-9]", "");
            adjustVolume(level);

        } else if (text.contains("打开")) {
            String appName = text.replace("打开", "").trim();
            openApp(appName);

        } else if (text.contains("截图")) {
            takeScreenshot();

        } else if (text.contains("退出") || text.contains("关闭")) {
            stopVoiceRecognition();
            floatWindowManager.showTip("再见");

        } else {
            floatWindowManager.showTip("没听懂");
        }

        if (isWakeWordEnabled) {
            stopVoiceRecognition();
        }
    }

    private String extractTextFromJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            return obj.optString("text");
        } catch (Exception e) {
            Log.e(TAG, "JSON解析失败: " + e.getMessage());
            return null;
        }
    }

    private void openNavigation(String destination) {
        Log.d(TAG, "打开导航: " + destination);
        try {
            Uri gmmIntentUri = Uri.parse("geo:0,0?q=" + destination);
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
            mapIntent.setPackage("com.autonavi.minimap");
            mapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (mapIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(mapIntent);
            } else {
                Uri webUri = Uri.parse("https://map.baidu.com/search/" + destination);
                Intent webIntent = new Intent(Intent.ACTION_VIEW, webUri);
                webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(webIntent);
            }

            floatWindowManager.showTip("正在导航到" + destination);
        } catch (Exception e) {
            Log.e(TAG, "打开导航失败: " + e.getMessage());
            floatWindowManager.showTip("无法打开导航");
        }
    }

    private void controlMusic(String command) {
        Log.d(TAG, "控制音乐: " + command);
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        Intent intent = new Intent("com.android.music.musicservicecommand");

        switch (command) {
            case "play":
                intent.putExtra("command", "play");
                sendBroadcast(intent);
                floatWindowManager.showTip("播放音乐");
                break;
            case "pause":
                intent.putExtra("command", "pause");
                sendBroadcast(intent);
                floatWindowManager.showTip("暂停");
                break;
            case "next":
                intent.putExtra("command", "next");
                sendBroadcast(intent);
                floatWindowManager.showTip("下一首");
                break;
        }
    }

    private void adjustVolume(String level) {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        int targetVolume;
        if (!level.isEmpty()) {
            try {
                int percent = Integer.parseInt(level);
                percent = Math.max(0, Math.min(100, percent));
                targetVolume = percent * maxVolume / 100;
            } catch (NumberFormatException e) {
                targetVolume = maxVolume / 2;
            }
        } else {
            int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            targetVolume = (current > 0) ? 0 : maxVolume / 2;
        }

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0);
        int percent = targetVolume * 100 / maxVolume;
        floatWindowManager.showTip("音量 " + percent + "%");
    }

    private void openApp(String appName) {
        Log.d(TAG, "打开应用: " + appName);

        String packageName = null;
        if (appName.contains("地图") || appName.contains("导航")) {
            packageName = "com.autonavi.minimap";
        } else if (appName.contains("音乐")) {
            packageName = "com.tencent.qqmusic";
        } else if (appName.contains("设置")) {
            packageName = "com.android.settings";
        } else if (appName.contains("视频")) {
            packageName = "com.youku.phone";
        } else if (appName.contains("微信")) {
            packageName = "com.tencent.mm";
        }

        if (packageName != null) {
            try {
                Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    floatWindowManager.showTip("打开" + appName);
                } else {
                    floatWindowManager.showTip("未安装" + appName);
                }
            } catch (Exception e) {
                Log.e(TAG, "打开应用失败: " + e.getMessage());
                floatWindowManager.showTip("打开失败");
            }
        } else {
            floatWindowManager.showTip("未知应用");
        }
    }

    private void takeScreenshot() {
        floatWindowManager.showTip("截图功能需要系统权限");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "服务销毁");

        stopVoiceRecognition();

        if (wakeWordService != null) {
            wakeWordService.stopListening();
        }

        if (floatWindowManager != null) {
            floatWindowManager.removeFloatBall();
        }

        try {
            unregisterReceiver(commandReceiver);
        } catch (Exception e) {
            // 忽略
        }

        Intent restartIntent = new Intent(this, VoiceAssistantService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent);
        } else {
            startService(restartIntent);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}