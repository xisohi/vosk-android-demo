package org.vosk.demo.service;

import android.content.Context;
import android.util.Log;

import java.io.File;

import ai.picovoice.porcupine.PorcupineManager;
import ai.picovoice.porcupine.PorcupineException;

public class PorcupineWakeWordService {
    private static final String TAG = "PorcupineWakeWord";

    private static final String ACCESS_KEY = "CxU8mHfeTo9E8qG85VUCdiftW1+l0CP9xJ3PCXqIMIecmrSSMNt1rQ==";

    // 检查这些路径是否正确
    private static final String MODEL_PATH = "models/porcupine_params_zh.pv";
    private static final String[] KEYWORD_PATHS = {
            "models/xiaogangpao.ppn",
            "models/xiaofei.ppn"
    };
    private static final float[] SENSITIVITIES = {0.7f, 0.75f};

    private PorcupineManager porcupineManager;
    private WakeWordListener listener;
    private boolean isInitialized = false;

    public interface WakeWordListener {
        void onWakeWordDetected(int keywordIndex);
    }

    public PorcupineWakeWordService(Context context, WakeWordListener listener) {
        this.listener = listener;

        // 添加详细日志：检查文件是否存在
        logFileStatus(context);

        try {
            Log.i(TAG, "开始初始化 Porcupine 4.0...");
            Log.i(TAG, "AccessKey: " + ACCESS_KEY.substring(0, 10) + "...");
            Log.i(TAG, "模型路径: " + MODEL_PATH);
            Log.i(TAG, "唤醒词路径: " + KEYWORD_PATHS[0] + ", " + KEYWORD_PATHS[1]);

            porcupineManager = new PorcupineManager.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(MODEL_PATH)
                    .setKeywordPaths(KEYWORD_PATHS)
                    .setSensitivities(SENSITIVITIES)
                    .build(context, keywordIndex -> {
                        Log.i(TAG, "检测到唤醒词索引: " + keywordIndex);
                        if (this.listener != null) {
                            this.listener.onWakeWordDetected(keywordIndex);
                        }
                    });

            isInitialized = true;
            Log.i(TAG, "Porcupine 4.0 初始化成功！");

        } catch (PorcupineException e) {
            Log.e(TAG, "初始化失败: " + e.getMessage());
            Log.e(TAG, "详细错误: ", e);
            isInitialized = false;
        }
    }

    /**
     * 检查模型文件状态
     */
    private void logFileStatus(Context context) {
        Log.i(TAG, "========== 文件检查 ==========");

        // 检查 assets 中的文件
        try {
            String[] assets = context.getAssets().list("models");
            if (assets != null) {
                Log.i(TAG, "assets/models 目录内容:");
                for (String f : assets) {
                    Log.i(TAG, "  - " + f);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "无法读取 assets/models: " + e.getMessage());
        }

        // 检查内部存储中的文件
        File[] files = {
                new File(context.getFilesDir(), "porcupine_params_zh.pv"),
                new File(context.getFilesDir(), "xiaogangpao.ppn"),
                new File(context.getFilesDir(), "xiaofei.ppn")
        };

        Log.i(TAG, "内部存储文件检查:");
        for (File f : files) {
            Log.i(TAG, "  " + f.getName() + ": " + (f.exists() ? "存在 (" + f.length() + "字节)" : "不存在"));
        }

        Log.i(TAG, "==============================");
    }

    public void start() {
        if (!isInitialized || porcupineManager == null) {
            Log.e(TAG, "无法启动：未初始化");
            return;
        }
        try {
            porcupineManager.start();
            Log.i(TAG, "开始监听唤醒词");
        } catch (PorcupineException e) {
            Log.e(TAG, "启动失败: " + e.getMessage() + ", 码: ");
        }
    }

    public void stop() {
        if (!isInitialized || porcupineManager == null) return;
        try {
            porcupineManager.stop();
            Log.i(TAG, "停止监听唤醒词");
        } catch (PorcupineException e) {
            Log.e(TAG, "停止失败: " + e.getMessage());
        }
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public WakeWordListener getListener() {
        return listener;
    }

    public void destroy() {
        if (porcupineManager != null) {
            porcupineManager.delete();
            porcupineManager = null;
            Log.i(TAG, "资源已释放");
        }
    }
}