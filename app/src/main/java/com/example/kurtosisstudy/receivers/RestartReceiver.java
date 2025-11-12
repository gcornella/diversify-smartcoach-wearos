package com.example.kurtosisstudy.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class RestartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String src = (intent != null && intent.hasExtra("src")) ? intent.getStringExtra("src") : "ALARM_ONE_SHOT";
        HeartbeatCheckWorker.checkAndRecover(context.getApplicationContext(), src);
    }
}
