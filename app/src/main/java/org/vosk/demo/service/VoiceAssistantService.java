package org.vosk.demo.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import org.vosk.demo.utils.AssetCopyUtil;

import java.io.File;
import java.io.IOException;

public class VoiceAssistantService extends Service {

    private static final String TAG = "VoiceAssistantService";
    private static final String CHANNEL_ID = "voice_assistant_channel";
    private static final int NOTIFICATION_ID = 1001;

    private static final String MODEL_ASSET_PATH = "vosk-model-small-cn-0.22";
    private static final String MODEL_INTERNAL_DIR = "models";

    private FloatWindowManager floatWindowManager;
    private PorcupineWakeWordService wakeWordService;
    private Model model;
    private SpeechService speechService;
    private Recognizer recognizer;
    private boolean isListening = false;
    private boolean isWakeWordEnabled = true;

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

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        floatWindowManager = new FloatWindowManager(this);
        initVosk();

        // 延迟初始化唤醒词
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            initWakeWordDetection();
        }, 2000);

        IntentFilter filter = new IntentFilter();
        filter.addAction("org.vosk.demo.START_LISTENING");
        filter.addAction("org.vosk.demo.STOP_LISTENING");
        ContextCompat.registerReceiver(this, commandReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        floatWindowManager.showFloatBall();
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

    // ==================== Vosk 模型初始化 ====================
    private void initVosk() {
        Log.d(TAG, "初始化Vosk");
        LibVosk.setLogLevel(LogLevel.INFO);

        File modelDir = new File(getFilesDir(), MODEL_INTERNAL_DIR + "/" + MODEL_ASSET_PATH);

        if (modelDir.exists()) {
            Log.d(TAG, "模型目录已存在，尝试加载");
            loadModel(modelDir.getAbsolutePath());
        } else {
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
                            Log.d(TAG, "模型解压成功");
                            runOnUiThread(() -> {
                                Toast.makeText(VoiceAssistantService.this, "模型解压完成", Toast.LENGTH_SHORT).show();
                            });
                            loadModel(new File(dir, MODEL_ASSET_PATH).getAbsolutePath());
                        }

                        @Override
                        public void onError() {
                            Log.e(TAG, "模型解压失败");
                            runOnUiThread(() -> {
                                Toast.makeText(VoiceAssistantService.this, "模型解压失败", Toast.LENGTH_LONG).show();
                            });
                        }
                    });
        }
    }

    private void loadModel(String path) {
        try {
            model = new Model(path);
            Log.d(TAG, "模型加载成功: " + path);
            runOnUiThread(() -> Toast.makeText(this, "模型加载成功", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            Log.e(TAG, "模型加载失败: " + e.getMessage());
            runOnUiThread(() -> Toast.makeText(this, "模型加载失败", Toast.LENGTH_LONG).show());
        }
    }

    // ==================== Porcupine 4.0 唤醒词初始化 ====================
    private void initWakeWordDetection() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "没有录音权限");
            showSafeTip("需要录音权限");
            return;
        }

        wakeWordService = new PorcupineWakeWordService(this, keywordIndex -> {
            String wakeWord = (keywordIndex == 0) ? "小钢炮" : "小飞";
            Log.d(TAG, "唤醒: " + wakeWord);

            ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 200);

            showSafeTip("唤醒: " + wakeWord);

            // 停止唤醒监听，启动语音识别
            wakeWordService.stop();
            startVoiceRecognition();
        });

        if (wakeWordService.isInitialized()) {
            wakeWordService.start();
            showSafeTip("语音助手就绪，说\"小钢炮\"或\"小飞\"唤醒");
        } else {
            Log.e(TAG, "唤醒词引擎初始化失败");
            showSafeTip("唤醒词初始化失败");
        }
    }

    private void stopWakeWordListening() {
        if (wakeWordService != null) {
            wakeWordService.stop();
        }
    }

    private void restartWakeWordListening() {
        if (isWakeWordEnabled && wakeWordService != null && wakeWordService.isInitialized()) {
            wakeWordService.start();
            showSafeTip("等待唤醒...");
        }
    }

    // ==================== Vosk 语音识别 ====================
    private void startVoiceRecognition() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            showSafeTip("缺少录音权限");
            return;
        }

        if (isListening) return;
        if (model == null) {
            showSafeTip("模型未加载");
            return;
        }

        try {
            recognizer = new Recognizer(model, 16000.0f);
            speechService = new SpeechService(recognizer, 16000.0f);
            speechService.startListening(new RecognitionListener() {
                @Override
                public void onResult(String hypothesis) {
                    processVoiceCommand(hypothesis);
                }

                @Override
                public void onFinalResult(String hypothesis) {
                    processVoiceCommand(hypothesis);
                }

                @Override
                public void onPartialResult(String hypothesis) {}

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "识别错误: " + e.getMessage());
                    isListening = false;
                    floatWindowManager.setListeningState(false);
                    restartWakeWordListening();
                }

                @Override
                public void onTimeout() {
                    stopVoiceRecognition();
                }
            });

            isListening = true;
            floatWindowManager.setListeningState(true);
            showSafeTip("请说话");

        } catch (IOException e) {
            Log.e(TAG, "启动识别失败: " + e.getMessage());
            showSafeTip("启动失败");
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

        // 5秒后恢复唤醒词监听
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            restartWakeWordListening();
        }, 5000);
    }

    // ==================== 指令处理 ====================
    private void processVoiceCommand(String jsonResult) {
        Log.d(TAG, "处理命令: " + jsonResult);
        String text = extractTextFromJson(jsonResult);
        if (text == null || text.isEmpty()) {
            stopVoiceRecognition();
            return;
        }

        text = text.toLowerCase();
        Log.d(TAG, "识别文本: " + text);
        showSafeTip("识别: " + text);

        if (text.contains("导航") || text.contains("去") || text.contains("到")) {
            String destination = text.replaceAll("导航|去|到", "").trim();
            if (!destination.isEmpty()) {
                openNavigation(destination);
            } else {
                showSafeTip("请说具体地点");
            }
        } else if (text.contains("播放") || text.contains("音乐")) {
            controlMusic("play");
        } else if (text.contains("暂停") || text.contains("停止")) {
            controlMusic("pause");
        } else if (text.contains("下一首")) {
            controlMusic("next");
        } else if (text.contains("音量")) {
            boolean up = text.contains("大") || text.contains("高") || text.contains("增");
            adjustVolume(up);
        } else if (text.contains("打开")) {
            String appName = text.replace("打开", "").trim();
            openApp(appName);
        } else if (text.contains("退出") || text.contains("关闭")) {
            showSafeTip("再见");
        } else {
            showSafeTip("没听懂: " + text);
        }

        stopVoiceRecognition();
    }

    private String extractTextFromJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            return obj.optString("text");
        } catch (Exception e) {
            return null;
        }
    }

    private void openNavigation(String destination) {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("androidamap://route/plan/?dname=" + Uri.encode(destination)));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            showSafeTip("导航到: " + destination);
        } catch (Exception e) {
            showSafeTip("请安装高德地图");
        }
    }

    private void controlMusic(String command) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        int keyCode;
        switch (command) {
            case "play": keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
            case "pause": keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case "next": keyCode = android.view.KeyEvent.KEYCODE_MEDIA_NEXT; break;
            default: return;
        }
        intent.putExtra(Intent.EXTRA_KEY_EVENT,
                new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode));
        sendBroadcast(intent);
        showSafeTip(command.equals("play") ? "播放音乐" :
                command.equals("pause") ? "暂停" : "下一首");
    }

    private void adjustVolume(boolean up) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int direction = up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        showSafeTip(up ? "音量增大" : "音量减小");
    }

    private void openApp(String appName) {
        showSafeTip("打开: " + appName);
    }

    // ==================== 工具方法 ====================
    private void showSafeTip(String message) {
        if (floatWindowManager != null) {
            floatWindowManager.showTip(message);
        }
    }

    private void runOnUiThread(Runnable runnable) {
        new Handler(getMainLooper()).post(runnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "服务销毁");

        stopWakeWordListening();

        if (wakeWordService != null) {
            wakeWordService.destroy();
        }

        stopVoiceRecognition();

        if (floatWindowManager != null) {
            floatWindowManager.removeFloatBall();
        }

        try {
            unregisterReceiver(commandReceiver);
        } catch (Exception e) {}

        // 重启服务保活
        Intent restartIntent = new Intent(this, VoiceAssistantService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent);
        } else {
            startService(restartIntent);
        }
    }
}