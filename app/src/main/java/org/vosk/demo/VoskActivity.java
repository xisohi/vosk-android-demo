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
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ToggleButton;

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

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import ai.picovoice.porcupine.Porcupine;
import org.json.JSONObject;
import org.json.JSONException;
import android.media.AudioManager;
import android.view.KeyEvent;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.media.ToneGenerator;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.AudioFormat;

public class VoskActivity extends Activity implements
        RecognitionListener {

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

    // Porcupine 唤醒词
    private static final String ACCESS_KEY = "CxU8mHfeTo9E8qG85VUCdiftW1+l0CP9xJ3PCXqIMIecmrSSMNt1rQ==";  // ← 替换为您的Key
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
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);

        // Setup layout
        resultView = findViewById(R.id.result_text);
        setUiState(STATE_START);

        findViewById(R.id.recognize_file).setOnClickListener(view -> recognizeFile());
        findViewById(R.id.recognize_mic).setOnClickListener(view -> recognizeMicrophone());
        ((ToggleButton) findViewById(R.id.pause)).setOnCheckedChangeListener((view, isChecked) -> pause(isChecked));

        LibVosk.setLogLevel(LogLevel.INFO);

        // Check if user has given permission to record audio, init the model after permission is granted
        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
        } else {
            initModel();
        }
    }

    private void initModel() {
        StorageService.unpack(this, "model-en-us", "model",
                (model) -> {
                    this.model = model;
                    setUiState(STATE_READY);
                    initPorcupine();  // 初始化唤醒词
                    startWakeWordDetection();  // 启动唤醒词监听
                },
                (exception) -> setErrorState("Failed to unpack the model" + exception.getMessage()));
    }

    // 初始化 Porcupine 唤醒词引擎
    private void initPorcupine() {
        try {
            porcupine = new Porcupine.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(MODEL_PATH)
                    .setKeywordPaths(KEYWORD_PATHS)
                    .setSensitivities(SENSITIVITIES)
                    .build(this);
            showResult("唤醒词初始化完成: 小钢炮, 小飞");
        } catch (Exception e) {
            setErrorState("唤醒词初始化失败: " + e.getMessage());
        }
    }

    // 启动唤醒词监听
    private void startWakeWordDetection() {
        isRunning = true;
        isWakeWordMode = true;

        int bufferSize = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        );

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize
        );

        audioRecord.startRecording();

        new Thread(() -> {
            short[] buffer = new short[512];
            while (isRunning) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0 && isWakeWordMode) {
                    int keywordIndex = porcupine.process(buffer);
                    if (keywordIndex >= 0) {
                        onWakeWordDetected(keywordIndex);
                    }
                }
            }
        }).start();
    }

    // 唤醒词检测回调
    private void onWakeWordDetected(int index) {
        String wakeWord = WAKE_WORD_NAMES[index];
        showResult("唤醒: " + wakeWord);

        // 播放提示音
        ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 200);

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

        // 5秒后返回唤醒词监听
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
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
        }
        if (porcupine != null) {
            porcupine.delete();
        }
        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
        }
        if (speechStreamService != null) {
            speechStreamService.stop();
        }
    }

    @Override
    public void onResult(String hypothesis) {
        // 解析指令并执行
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
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("androidamap://route/plan/?dname=" + Uri.encode(destination)));
        try {
            startActivity(intent);
            showResult("导航到: " + destination);
        } catch (Exception e) {
            showResult("请安装高德地图");
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
    }

    private void showResult(String text) {
        handler.post(() -> resultView.append(text + "\n"));
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

                InputStream ais = getAssets().open(
                        "10001-90210-01803.wav");
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