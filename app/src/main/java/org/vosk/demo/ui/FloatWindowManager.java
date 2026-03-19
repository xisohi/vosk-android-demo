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
    
    // 悬浮窗参数
    private WindowManager.LayoutParams layoutParams;
    
    public FloatWindowManager(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        
        // 初始化悬浮窗参数
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
    
    // 根据Android版本获取正确的窗口类型
    private int getWindowManagerType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            return WindowManager.LayoutParams.TYPE_PHONE;
        }
    }
    
    // 显示悬浮球
    public void showFloatBall() {
        if (floatBall != null) return;
        
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "请在设置中开启悬浮窗权限", Toast.LENGTH_LONG).show();
                
                // 跳转到权限设置页面
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            }
        }
        
        // 加载悬浮球布局
        LayoutInflater inflater = LayoutInflater.from(context);
        floatBall = inflater.inflate(R.layout.float_ball, null);
        
        ImageView ballIcon = floatBall.findViewById(R.id.ball_icon);
        TextView ballText = floatBall.findViewById(R.id.ball_text);
        
        // 设置初始状态
        ballIcon.setImageResource(isListening ? 
                android.R.drawable.ic_media_play : android.R.drawable.ic_btn_speak_now);
        ballText.setText(isListening ? "听" : "语");
        
        // 点击悬浮球：切换监听状态
        floatBall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleListening();
            }
        });
        
        // 长按悬浮球：进入拖拽模式
        floatBall.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                startDragging();
                return true;
            }
        });
        
        // 添加到窗口
        windowManager.addView(floatBall, layoutParams);
        isShowing = true;
    }
    
    // 切换监听状态
    private void toggleListening() {
        isListening = !isListening;
        
        ImageView ballIcon = floatBall.findViewById(R.id.ball_icon);
        TextView ballText = floatBall.findViewById(R.id.ball_text);
        
        ballIcon.setImageResource(isListening ? 
                android.R.drawable.ic_media_play : android.R.drawable.ic_btn_speak_now);
        ballText.setText(isListening ? "听" : "语");
        
        if (isListening) {
            showTip("我在听");
            // 发送广播通知服务开始识别
            Intent intent = new Intent("org.vosk.demo.START_LISTENING");
            context.sendBroadcast(intent);
        } else {
            showTip("暂停");
            Intent intent = new Intent("org.vosk.demo.STOP_LISTENING");
            context.sendBroadcast(intent);
        }
    }
    
    // 开始拖拽
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
    
    // 显示提示
    public void showTip(String message) {
        if (tipView == null) {
            LayoutInflater inflater = LayoutInflater.from(context);
            tipView = inflater.inflate(R.layout.float_tip, null);
            
            // 提示窗口参数（居中显示）
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
        
        // 2秒后隐藏
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
    
    // 设置监听状态（用于同步）
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
    
    // 移除悬浮球
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
    
    // 检查是否正在显示
    public boolean isShowing() {
        return isShowing;
    }
}
