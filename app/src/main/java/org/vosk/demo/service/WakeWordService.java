package org.vosk.demo.service;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

public class WakeWordService {

    private static final String TAG = "WakeWordService";
    private static final int SAMPLE_RATE = 16000;
    private static int ENERGY_THRESHOLD = 1000; // 能量阈值，可调整

    private boolean isListening = false;
    private AudioRecord audioRecord;
    private Thread listeningThread;
    private WakeWordListener listener;

    // 唤醒词监听接口
    public interface WakeWordListener {
        void onWakeWordDetected();
    }

    public WakeWordService() {
    }

    // 设置监听器
    public void setWakeWordListener(WakeWordListener listener) {
        this.listener = listener;
    }

    // 开始监听唤醒词
    public void startListening() {
        if (isListening) return;

        isListening = true;

        listeningThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    startAudioCapture();
                } catch (SecurityException e) {
                    Log.e(TAG, "录音权限被拒绝: " + e.getMessage());
                    // 通知外部权限问题（可通过listener添加回调，这里简化处理）
                } catch (Exception e) {
                    Log.e(TAG, "音频捕获异常: " + e.getMessage());
                }
            }
        });
        listeningThread.start();

        Log.d(TAG, "唤醒词监听已启动");
    }

    // 停止监听唤醒词
    public void stopListening() {
        isListening = false;

        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            } catch (Exception e) {
                Log.e(TAG, "停止录音时出错: " + e.getMessage());
            }
        }

        if (listeningThread != null) {
            listeningThread.interrupt();
            listeningThread = null;
        }

        Log.d(TAG, "唤醒词监听已停止");
    }

    // 开始音频捕获和能量检测
    private void startAudioCapture() throws SecurityException {
        int bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        // 如果bufferSize太小，设为一个合理的值
        if (bufferSize < 2048) {
            bufferSize = 4096;
        }

        // 创建 AudioRecord（可能抛出 SecurityException）
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败");
            return;
        }

        try {
            audioRecord.startRecording();
        } catch (SecurityException e) {
            Log.e(TAG, "启动录音时权限被拒绝: " + e.getMessage());
            throw e; // 重新抛出，让上层处理
        }

        short[] buffer = new short[bufferSize];

        Log.d(TAG, "开始音频捕获，bufferSize: " + bufferSize);

        while (isListening && !Thread.currentThread().isInterrupted()) {
            int read = 0;
            try {
                read = audioRecord.read(buffer, 0, buffer.length);
            } catch (SecurityException e) {
                Log.e(TAG, "读取录音数据时权限被拒绝: " + e.getMessage());
                break;
            } catch (Exception e) {
                Log.e(TAG, "读取录音数据异常: " + e.getMessage());
                break;
            }

            if (read > 0) {
                // 计算能量（简单的平方和平均）
                long sum = 0;
                for (int i = 0; i < read; i++) {
                    sum += Math.abs(buffer[i]);
                }
                long energy = sum / read;

                // 如果能量超过阈值，可能是有人在说话
                if (energy > ENERGY_THRESHOLD) {
                    Log.d(TAG, "检测到声音能量: " + energy);

                    // 这里应该用更复杂的算法检测唤醒词
                    // 简单起见，假设大声说话就是唤醒
                    if (listener != null) {
                        listener.onWakeWordDetected();

                        // 避免频繁唤醒，休眠2秒
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
            }
        }

        // 清理资源
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            } catch (Exception e) {
                Log.e(TAG, "释放录音资源异常: " + e.getMessage());
            }
        }

        Log.d(TAG, "音频捕获结束");
    }

    // 检查是否正在监听
    public boolean isListening() {
        return isListening;
    }

    // 设置能量阈值（可根据环境调整）
    public void setEnergyThreshold(int threshold) {
        ENERGY_THRESHOLD = threshold;
    }
}