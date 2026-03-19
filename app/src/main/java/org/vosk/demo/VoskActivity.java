// Copyright 2019 Alpha Cephei Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.vosk.demo;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.SpeechStreamService;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;

import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.porcupine.PorcupineException;

public class VoskActivity extends Activity implements RecognitionListener {

    static private final int STATE_START = 0;
    static private final int STATE_READY = 1;
    static private final int STATE_DONE = 2;
    static private final int STATE_FILE = 3;
    static private final int STATE_MIC = 4;

    /* Used to handle permission request */
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;

    private Model model;
    private SpeechService speechService;
    private SpeechStreamService speechStreamService;
    private TextView resultView;

    // Porcupine 唤醒词配置
    private static final String ACCESS_KEY = "CxU8mHfeTo9E8qG85VUCdiftW1+l0CP9xJ3PCXqIMIecmrSSMNt1rQ==";  // 替换为你的有效 AccessKey
    private static final String MODEL_PATH = "models/porcupine_params_zh.pv";
    private static final String[] KEYWORD_PATHS = {
            "models/xiaogangpao.ppn",
            "models/xiaofei.ppn"
    };
    private static final String[] WAKE_WORD_NAMES = {"小钢炮", "小飞"};
    private static final float[] SENSITIVITIES = {0.7f, 0.75f};

    private Porcupine porcupine;
    private AudioRecord audioRecord;
    private boolean isRunning = false;
    private boolean isWakeWordMode = true;
    
    // 使用静态内部类和弱引用避免内存泄漏
    private static class SafeHandler extends Handler {
        private final WeakReference<VoskActivity> activityRef;
        
        SafeHandler(VoskActivity activity) {
            super(Looper.getMainLooper());
            this.activityRef = new WeakReference<>(activity);
        }
    }
    private SafeHandler handler;
    
    // 音频焦点管理
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus = false;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);

        // 初始化Handler
        handler = new SafeHandler(this);

        // Setup layout
        resultView = findViewById(R.id.result_text);
        setUiState(STATE_START);

        findViewById(R.id.recognize_file).setOnClickListener(view -> recognizeFile());
        findViewById(R.id.recognize_mic).setOnClickListener(view -> recognizeMicrophone());
        ((ToggleButton) findViewById(R.id.pause)).setOnCheckedChangeListener((view, isChecked) -> pause(isChecked));

        LibVosk.setLogLevel(LogLevel.INFO);
        
        // 初始化音频管理器
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        // 检查设备是否有麦克风
        PackageManager pm = getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            showResult("该设备没有麦克风");
            return;
        }

        // Check if user has given permission to record audio, init the model after permission is granted
        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
        } else {
            initModel();
        }
    }

    private void initModel() {
        showResult("正在加载语音模型，请稍候...");
        StorageService.unpack(this, "model-en-us", "model",
                (model) -> {
                    this.model = model;
                    showResult("模型加载完成");
                    setUiState(STATE_READY);
                    initPorcupine();  // 初始化唤醒词
                    requestAudioFocus(); // 请求音频焦点
                    startWakeWordDetection();  // 启动唤醒词监听
                },
                (exception) -> setErrorState("模型加载失败: " + exception.getMessage()));
    }

    // 初始化 Porcupine 唤醒词引擎
    private void initPorcupine() {
        try {
            // 注意：必须传入 Context (this)
            porcupine = new Porcupine.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(MODEL_PATH)
                    .setKeywordPaths(KEYWORD_PATHS)
                    .setSensitivities(SENSITIVITIES)
                    .build(this);   // 关键修改：添加 this
            showResult("唤醒词初始化完成: 小钢炮, 小飞");
        } catch (PorcupineException e) {
            setErrorState("唤醒词初始化失败: " + e.getMessage());
        }
    }

    // 请求音频焦点（Android 10 车机适配）
    private void requestAudioFocus() {
        if (audioManager == null) return;
        
        AudioManager.OnAudioFocusChangeListener focusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
            @Override
            public void onAudioFocusChange(int focusChange) {
                switch (focusChange) {
                    case AudioManager.AUDIOFOCUS_LOSS:
                        // 长时间失去焦点，应该停止语音识别
                        hasAudioFocus = false;
                        isWakeWordMode = false;
                        if (speechService != null) {
                            speechService.stop();
                        }
                        showResult("失去音频焦点");
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        // 短暂失去焦点，暂停识别
                        hasAudioFocus = false;
                        isWakeWordMode = false;
                        break;
                    case AudioManager.AUDIOFOCUS_GAIN:
                        // 重新获得焦点，恢复识别
                        hasAudioFocus = true;
                        isWakeWordMode = true;
                        showResult("重新获得音频焦点");
                        break;
                }
            }
        };

        // Android 10 及以上使用 AudioFocusRequest
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build();
            
            int result = audioManager.requestAudioFocus(audioFocusRequest);
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        } else {
            // 旧版本兼容
            int result = audioManager.requestAudioFocus(focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        }
        
        if (!hasAudioFocus) {
            showResult("无法获取音频焦点，可能影响语音识别");
        }
    }

    // 释放音频焦点
    private void abandonAudioFocus() {
        if (audioManager == null) return;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(null);
        }
        hasAudioFocus = false;
    }

    // 启动唤醒词监听
    private void startWakeWordDetection() {
        if (!hasAudioFocus) {
            showResult("等待音频焦点...");
            return;
        }
        
        isRunning = true;
        isWakeWordMode = true;

        int bufferSize = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        );

        // 使用 VOICE_RECOGNITION 音频源，更适合语音识别
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2  // 缓冲区稍大，避免溢出
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            setErrorState("麦克风初始化失败");
            return;
        }

        audioRecord.startRecording();

        new Thread(() -> {
            short[] buffer = new short[512];
            while (isRunning && hasAudioFocus) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0 && isWakeWordMode) {
                    try {
                        int keywordIndex = porcupine.process(buffer);
                        if (keywordIndex >= 0) {
                            onWakeWordDetected(keywordIndex);
                        }
                    } catch (PorcupineException e) {
                        // 记录异常，继续运行
                        e.printStackTrace();
                    }
                }
                
                // 简单节电：当没有声音时稍微休眠（需要配合VAD更精确）
                try {
                    Thread.sleep(0, 500000); // 0.5ms 微休眠
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }

    // 唤醒词检测回调
    private void onWakeWordDetected(int index) {
        String wakeWord = WAKE_WORD_NAMES[index];
        showResult("唤醒: " + wakeWord);

        // 播放提示音，使用助理用途的音频属性
        ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 200);
        toneGen.release();  // 立即释放资源

        // 切换到指令识别模式
        isWakeWordMode = false;
        handler.post(() -> {
            if (speechService != null) {
                speechService.stop();
            }
            try {
                Recognizer rec = new Recognizer(model, 16000.0f);
                speechService = new SpeechService(rec, 16000.0f);
                speechService.startListening(this);
                showResult("请说指令: 导航/音量/播放/暂停...");
            } catch (IOException e) {
                setErrorState("指令识别启动失败: " + e.getMessage());
            }
        });

        // 5秒后返回唤醒词监听（节电设计）
        handler.postDelayed(() -> {
            isWakeWordMode = true;
            if (speechService != null) {
                speechService.stop();
                speechService = null;
            }
            showResult("返回唤醒词监听...");
        }, 5000);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initModel();
            } else {
                finish();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 暂停唤醒检测，节省资源
        isRunning = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 恢复运行
        if (model != null && porcupine != null && hasAudioFocus) {
            isRunning = true;
            startWakeWordDetection();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        
        // 释放音频焦点
        abandonAudioFocus();
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
            audioRecord = null;
        }
        
        if (porcupine != null) {
            porcupine.delete();
            porcupine = null;
        }
        
        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
            speechService = null;
        }
        
        if (speechStreamService != null) {
            speechStreamService.stop();
            speechStreamService = null;
        }
        
        // 移除所有Handler回调，避免内存泄漏
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onResult(String hypothesis) {
        processCommand(hypothesis);
        resultView.append(hypothesis + "\n");
    }

    // 处理识别到的指令
    private void processCommand(String hypothesis) {
        try {
            JSONObject json = new JSONObject(hypothesis);
            String text = json.optString("text", "");

            if (text.isEmpty()) return;

            showResult("识别: " + text);

            if (text.contains("导航") || text.contains("去")) {
                String dest = text.replace("导航", "").replace("去", "").trim();
                startNavigation(dest);
            } else if (text.contains("音量")) {
                boolean up = text.contains("大") || text.contains("高") || text.contains("增");
                adjustVolume(up);
            } else if (text.contains("播放") || text.contains("音乐")) {
                controlMusic("play");
            } else if (text.contains("暂停") || text.contains("停止") || text.contains("关闭")) {
                controlMusic("pause");
            }
        } catch (JSONException e) {
            showResult("指令解析失败");
        }
    }

    // 执行导航
    private void startNavigation(String destination) {
        if (destination.isEmpty()) {
            showResult("未听清目的地");
            return;
        }
        
        // 请求音频焦点（导航会占用焦点）
        if (hasAudioFocus) {
            abandonAudioFocus();
        }
        
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("androidamap://route/plan/?dname=" + Uri.encode(destination)));
        try {
            startActivity(intent);
            showResult("导航到: " + destination);
        } catch (Exception e) {
            showResult("请安装高德地图");
            // 重新获取焦点
            requestAudioFocus();
        }
    }

    // 调节音量
    private void adjustVolume(boolean up) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int direction = up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        showResult(up ? "音量增大" : "音量减小");
    }

    // 控制音乐
    private void controlMusic(String action) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        int keyCode = action.equals("play") ?
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE : KeyEvent.KEYCODE_MEDIA_PAUSE;
        intent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        sendBroadcast(intent);
        showResult(action.equals("play") ? "播放音乐" : "暂停音乐");
        
        // 音乐播放后可以重新获取焦点
        if (!hasAudioFocus) {
            requestAudioFocus();
        }
    }

    private void showResult(String text) {
        handler.post(() -> {
            VoskActivity activity = ((SafeHandler) handler).activityRef.get();
            if (activity != null && !activity.isFinishing()) {
                resultView.append(text + "\n");
            }
        });
    }

    @Override
    public void onFinalResult(String hypothesis) {
        resultView.append(hypothesis + "\n");
        setUiState(STATE_DONE);
        if (speechStreamService != null) {
            speechStreamService = null;
        }
    }

    @Override
    public void onPartialResult(String hypothesis) {
        resultView.append(hypothesis + "\n");
    }

    @Override
    public void onError(Exception e) {
        setErrorState(e.getMessage());
    }

    @Override
    public void onTimeout() {
        setUiState(STATE_DONE);
    }

    private void setUiState(int state) {
        switch (state) {
            case STATE_START:
                resultView.setText(R.string.preparing);
                resultView.setMovementMethod(new ScrollingMovementMethod());
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_READY:
                resultView.setText(R.string.ready);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_DONE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.recognize_file);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                ((ToggleButton) findViewById(R.id.pause)).setChecked(false);
                break;
            case STATE_FILE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.stop_file);
                resultView.setText(getString(R.string.starting));
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_MIC:
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.stop_microphone);
                resultView.setText(getString(R.string.say_something));
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((true));
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + state);
        }
    }

    private void setErrorState(String message) {
        resultView.setText(message);
        ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
        findViewById(R.id.recognize_file).setEnabled(false);
        findViewById(R.id.recognize_mic).setEnabled(false);
    }

    private void recognizeFile() {
        if (speechStreamService != null) {
            setUiState(STATE_DONE);
            speechStreamService.stop();
            speechStreamService = null;
        } else {
            setUiState(STATE_FILE);
            try {
                Recognizer rec = new Recognizer(model, 16000.f, "[\"one zero zero zero one\", " +
                        "\"oh zero one two three four five six seven eight nine\", \"[unk]\"]");

                InputStream ais = getAssets().open("10001-90210-01803.wav");
                if (ais.skip(44) != 44) throw new IOException("File too short");

                speechStreamService = new SpeechStreamService(rec, ais, 16000);
                speechStreamService.start(this);
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }

    private void recognizeMicrophone() {
        if (speechService != null) {
            setUiState(STATE_DONE);
            speechService.stop();
            speechService = null;
        } else {
            setUiState(STATE_MIC);
            try {
                Recognizer rec = new Recognizer(model, 16000.0f);
                speechService = new SpeechService(rec, 16000.0f);
                speechService.startListening(this);
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }

    private void pause(boolean checked) {
        if (speechService != null) {
            speechService.setPause(checked);
        }
    }
}
