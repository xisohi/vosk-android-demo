package org.vosk.demo.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.vosk.demo.R;

public class FloatWindowManager {

    private Context context;
    private WindowManager windowManager;
    private View floatBall;
    private View tipView;
    private boolean isShowing = false;
    private boolean isListening = false;

    private WindowManager.LayoutParams layoutParams;

    public FloatWindowManager(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getWindowManagerType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = 100;
        layoutParams.y = 200;
    }

    private int getWindowManagerType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            return WindowManager.LayoutParams.TYPE_PHONE;
        }
    }

    public void showFloatBall() {
        if (floatBall != null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "请在设置中开启悬浮窗权限", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            }
        }

        LayoutInflater inflater = LayoutInflater.from(context);
        floatBall = inflater.inflate(R.layout.float_ball, null);

        ImageView ballIcon = floatBall.findViewById(R.id.ball_icon);
        TextView ballText = floatBall.findViewById(R.id.ball_text);

        ballIcon.setImageResource(isListening ?
                android.R.drawable.ic_media_play : android.R.drawable.ic_btn_speak_now);
        ballText.setText(isListening ? "听" : "语");

        floatBall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleListening();
            }
        });

        floatBall.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                startDragging();
                return true;
            }
        });

        windowManager.addView(floatBall, layoutParams);
        isShowing = true;
    }

    private void toggleListening() {
        isListening = !isListening;

        ImageView ballIcon = floatBall.findViewById(R.id.ball_icon);
        TextView ballText = floatBall.findViewById(R.id.ball_text);

        ballIcon.setImageResource(isListening ?
                android.R.drawable.ic_media_play : android.R.drawable.ic_btn_speak_now);
        ballText.setText(isListening ? "听" : "语");

        if (isListening) {
            showTip("我在听");
            Intent intent = new Intent("org.vosk.demo.START_LISTENING");
            context.sendBroadcast(intent);
        } else {
            showTip("暂停");
            Intent intent = new Intent("org.vosk.demo.STOP_LISTENING");
            context.sendBroadcast(intent);
        }
    }

    private void startDragging() {
        floatBall.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = layoutParams.x;
                        initialY = layoutParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        layoutParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        layoutParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatBall, layoutParams);
                        return true;

                    case MotionEvent.ACTION_UP:
                        floatBall.setOnTouchListener(null);
                        return true;
                }
                return false;
            }
        });
    }

    /**
     * 安全显示提示：优先使用悬浮窗，若无权限则改用 Toast
     */
    public void showTip(String message) {
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                // 无权限，使用 Toast
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // 有权限，使用悬浮窗提示
        if (tipView == null) {
            LayoutInflater inflater = LayoutInflater.from(context);
            tipView = inflater.inflate(R.layout.float_tip, null);

            WindowManager.LayoutParams tipParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    getWindowManagerType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
            );
            tipParams.gravity = Gravity.CENTER;

            windowManager.addView(tipView, tipParams);
        }

        TextView tipText = tipView.findViewById(R.id.tip_text);
        tipText.setText(message);

        tipView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (tipView != null) {
                    windowManager.removeView(tipView);
                    tipView = null;
                }
            }
        }, 2000);
    }

    public void setListeningState(boolean listening) {
        this.isListening = listening;
        if (floatBall != null) {
            ImageView ballIcon = floatBall.findViewById(R.id.ball_icon);
            TextView ballText = floatBall.findViewById(R.id.ball_text);
            ballIcon.setImageResource(listening ?
                    android.R.drawable.ic_media_play : android.R.drawable.ic_btn_speak_now);
            ballText.setText(listening ? "听" : "语");
        }
    }

    public void removeFloatBall() {
        if (floatBall != null) {
            windowManager.removeView(floatBall);
            floatBall = null;
        }
        if (tipView != null) {
            windowManager.removeView(tipView);
            tipView = null;
        }
        isShowing = false;
    }

    public boolean isShowing() {
        return isShowing;
    }
}