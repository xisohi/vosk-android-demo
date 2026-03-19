package org.vosk.demo.service;

import android.app.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.vosk.demo.R;
import org.vosk.demo.ui.FloatWindowManager;
import org.vosk.demo.VoskActivity;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.SpeechStreamService;
import org.vosk.android.StorageService;

import java.io.IOException;

public class VoiceAssistantService extends Service {
    
    private static final String TAG = "VoiceAssistantService";
    private static final String CHANNEL_ID = "voice_assistant_channel";
    private static final int NOTIFICATION_ID = 1001;
    
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
        
        // 初始化Vosk
        initVosk();
        
        // 初始化唤醒词检测
        initWakeWordDetection();
        
        // 注册广播接收器
        registerReceiver(commandReceiver, new IntentFilter("org.vosk.demo.START_LISTENING"));
        registerReceiver(commandReceiver, new IntentFilter("org.vosk.demo.STOP_LISTENING"));
        
        // 显示悬浮球
        floatWindowManager.showFloatBall();
        
        // 如果唤醒词功能开启，开始监听
        if (isWakeWordEnabled && wakeWordService != null) {
            wakeWordService.startListening();
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
    
    private void initVosk() {
        Log.d(TAG, "初始化Vosk");
        
        // 设置Vosk日志级别
        LibVosk.setLogLevel(LogLevel.INFO);
        
        // 加载模型（这里假设模型文件在 /sdcard/vosk-model-small-cn-0.22）
        // 实际使用时，需要用户先下载模型放到指定目录
        try {
            // 检查模型是否存在
            String modelPath = "/sdcard/vosk-model-small-cn-0.22";
            java.io.File modelFile = new java.io.File(modelPath);
            
            if (!modelFile.exists()) {
                Log.e(TAG, "模型文件不存在: " + modelPath);
                Toast.makeText(this, "请先下载语音模型", Toast.LENGTH_LONG).show();
                return;
            }
            
            model = new Model(modelPath);
            Log.d(TAG, "模型加载成功");
        } catch (Exception e) {
            Log.e(TAG, "模型加载失败: " + e.getMessage());
        }
    }
    
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
                        // 显示提示
                        if (floatWindowManager != null) {
                            floatWindowManager.showTip("我在");
                        }
                        
                        // 开始语音识别
                        startVoiceRecognition();
                    }
                });
            }
        });
    }
    
    private void runOnUiThread(Runnable runnable) {
        // 由于Service没有runOnUiThread方法，我们用Handler
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(runnable);
    }
    
    private void startVoiceRecognition() {
        if (isListening) return;
        
        if (model == null) {
            Log.e(TAG, "模型未初始化");
            floatWindowManager.showTip("模型未加载");
            return;
        }
        
        try {
            // 创建识别器
            recognizer = new Recognizer(model, 16000.0f);
            
            // 创建语音服务
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
                    // 部分结果，可以忽略或用于实时显示
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
            wakeWordService.startListening();
        }
    }
    
    private void processVoiceCommand(String jsonResult) {
        Log.d(TAG, "处理命令: " + jsonResult);
        
        // 解析JSON，提取识别的文本
        String text = extractTextFromJson(jsonResult);
        if (text == null || text.isEmpty()) {
            return;
        }
        
        text = text.toLowerCase();
        Log.d(TAG, "识别文本: " + text);
        
        // 简单的命令解析
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
            // 提取数字
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
        
        // 识别完成后，如果唤醒词功能开启，继续监听唤醒词
        if (isWakeWordEnabled) {
            stopVoiceRecognition();
        }
    }
    
    private String extractTextFromJson(String json) {
        // 简单的JSON解析，Vosk返回格式: {"text": "你好"}
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
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
            mapIntent.setPackage("com.autonavi.minimap"); // 优先用高德
            mapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            // 如果没有高德，用浏览器打开
            if (mapIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(mapIntent);
            } else {
                // 用浏览器打开百度地图
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
                
                // 尝试播放本地音乐（需要实际实现）
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
            // 按数字调节
            try {
                int percent = Integer.parseInt(level);
                percent = Math.max(0, Math.min(100, percent)); // 限制在0-100
                targetVolume = percent * maxVolume / 100;
            } catch (NumberFormatException e) {
                targetVolume = maxVolume / 2;
            }
        } else {
            // 没有数字，切换静音
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
            packageName = "com.autonavi.minimap"; // 高德地图
        } else if (appName.contains("音乐")) {
            packageName = "com.tencent.qqmusic"; // QQ音乐
        } else if (appName.contains("设置")) {
            packageName = "com.android.settings";
        } else if (appName.contains("视频")) {
            packageName = "com.youku.phone"; // 优酷
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
        // 截图需要系统权限，简单提示
        floatWindowManager.showTip("截图功能需要系统权限");
        // 实际实现需要 MediaProjection API
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        return START_STICKY; // 服务被杀死后自动重启
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "服务销毁");
        
        // 停止所有
        stopVoiceRecognition();
        
        if (wakeWordService != null) {
            wakeWordService.stopListening();
        }
        
        if (floatWindowManager != null) {
            floatWindowManager.removeFloatBall();
        }
        
        // 注销广播接收器
        try {
            unregisterReceiver(commandReceiver);
        } catch (Exception e) {
            // 忽略
        }
        
        // 尝试重启服务
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
