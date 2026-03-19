package org.vosk.demo.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.vosk.demo.service.VoiceAssistantService;

public class BootReceiver extends BroadcastReceiver {
    
    private static final String TAG = "BootReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "收到广播: " + action);
        
        // 检查是否是开机完成的广播
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_QUICKBOOT_POWERON.equals(action) ||
                "android.intent.action.REBOOT".equals(action)) {
            
            Log.d(TAG, "系统启动完成，开始启动语音助手服务");
            
            // 启动语音助手服务
            startVoiceService(context);
        }
    }
    
    private void startVoiceService(Context context) {
        Intent serviceIntent = new Intent(context, VoiceAssistantService.class);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0 以上需要 startForegroundService
            context.startForegroundService(serviceIntent);
        } else {
            // 低版本直接 startService
            context.startService(serviceIntent);
        }
        
        Log.d(TAG, "语音助手服务已启动");
    }
}
