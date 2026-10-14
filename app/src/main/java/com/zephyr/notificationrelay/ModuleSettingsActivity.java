package com.zephyr.notificationrelay;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

public final class ModuleSettingsActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            startActivity(new Intent("com.milink.service.notification.SUBSCRIPTION_APP")
                    .setPackage("com.milink.service")
                    .putExtra("zephyr_open_app_list", true));
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(this, "无法打开跨设备通知设置", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
