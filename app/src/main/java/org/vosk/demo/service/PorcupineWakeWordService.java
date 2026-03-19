package org.vosk.demo.service;

import android.content.Context;
import android.util.Log;

import java.io.File;

import ai.picovoice.porcupine.PorcupineManager;
import ai.picovoice.porcupine.PorcupineException;

public class PorcupineWakeWordService {

    private static final String TAG = "PorcupineWakeWord";

    private static final String ACCESS_KEY = "LmayZpsbgbISnP7nXP9CWXm3kzpDfItMkaQfblUOGWcBnMsG7kK1nw=="; // ⚠️ 建议换新key

    private PorcupineManager porcupineManager;
    private WakeWordListener listener;
    private boolean isInitialized = false;

    public interface WakeWordListener {
        void onWakeWordDetected(int keywordIndex);
    }

    public PorcupineWakeWordService(Context context, WakeWordListener listener) {
        this.listener = listener;

        // ✅ 获取内部存储路径
        File baseDir = context.getFilesDir();

        String modelPath = new File(baseDir, "porcupine_params_zh.pv").getAbsolutePath();

        String[] keywordPaths = {
                new File(baseDir, "xiaogangpao.ppn").getAbsolutePath(),
                new File(baseDir, "xiaofei.ppn").getAbsolutePath()
        };

        float[] sensitivities = {0.7f, 0.75f};

        // ✅ 打印路径检查（非常重要）
        logFileStatus(baseDir, modelPath, keywordPaths);

        try {
            Log.i(TAG, "开始初始化 Porcupine 4.0...");
            Log.i(TAG, "AccessKey: " + ACCESS_KEY.substring(0, 10) + "...");
            Log.i(TAG, "modelPath: " + modelPath);

            porcupineManager = new PorcupineManager.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(modelPath)          // ✅ 绝对路径
                    .setKeywordPaths(keywordPaths)    // ✅ 绝对路径
                    .setSensitivities(sensitivities)
                    .build(context, keywordIndex -> {
                        Log.i(TAG, "检测到唤醒词索引: " + keywordIndex);
                        if (this.listener != null) {
                            this.listener.onWakeWordDetected(keywordIndex);
                        }
                    });

            isInitialized = true;
            Log.i(TAG, "✅ Porcupine 初始化成功！");

        } catch (PorcupineException e) {
            Log.e(TAG, "❌ 初始化失败: " + e.getMessage());
            Log.e(TAG, "详细错误: ", e);
            isInitialized = false;
        }
    }

    /**
     * 文件检查（增强版）
     */
    private void logFileStatus(File baseDir, String modelPath, String[] keywordPaths) {
        Log.i(TAG, "========== 文件检查 ==========");
        Log.i(TAG, "baseDir: " + baseDir.getAbsolutePath());

        File modelFile = new File(modelPath);
        Log.i(TAG, "模型文件: " + modelFile.getAbsolutePath() +
                " | 存在=" + modelFile.exists() +
                " | 大小=" + modelFile.length());

        for (String path : keywordPaths) {
            File f = new File(path);
            Log.i(TAG, "唤醒词文件: " + f.getAbsolutePath() +
                    " | 存在=" + f.exists() +
                    " | 大小=" + f.length());
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
            Log.i(TAG, "🎤 开始监听唤醒词");
        } catch (PorcupineException e) {
            Log.e(TAG, "启动失败: " + e.getMessage(), e);
        }
    }

    public void stop() {
        if (!isInitialized || porcupineManager == null) return;
        try {
            porcupineManager.stop();
            Log.i(TAG, "⏹ 停止监听唤醒词");
        } catch (PorcupineException e) {
            Log.e(TAG, "停止失败: " + e.getMessage(), e);
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
            Log.i(TAG, "🧹 资源已释放");
        }
    }
}