package com.zephyr.notificationrelay;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class DiagnosticsActivity extends Activity {
    private TextView output;
    private String report = "";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom);
            return insets;
        });
        TextView title = new TextView(this);
        title.setText("跨设备通知入口诊断");
        title.setTextSize(22);
        layout.addView(title);
        TextView help = new TextView(this);
        help.setText("1. 在 LSPosed 中启用本模块，勾选米联并重启其作用域。\n2. 从系统设置点击“设备互联”，停在出现问题的原页面；若能看到“跨设备通知”，再点击一次。\n3. 返回此页刷新并导出日志。\n\n日志仅包含模块与系统版本、进程、页面类名、Hook 阶段及异常类型。若没有加载记录，请同时导出 LSPosed 日志；模块要求现代 API 101。\n");
        layout.addView(help);
        Button refresh = new Button(this);
        refresh.setText("刷新日志");
        refresh.setOnClickListener(view -> refresh());
        layout.addView(refresh);
        Button export = new Button(this);
        export.setText("导出日志文件");
        export.setOnClickListener(view -> {
            refresh();
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "NotificationRelay-entry-diagnostic.txt"), 1);
        });
        layout.addView(export);
        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        scroll.addView(output);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        layout.requestApplyInsets();
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private String version(String name) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(name, 0);
            return info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (Exception error) { return "unavailable: " + error.getClass().getSimpleName(); }
    }

    private void refresh() {
        StringBuilder text = new StringBuilder("NotificationRelay entry diagnostic\n")
                .append("module=").append(BuildConfig.VERSION_NAME).append('\n')
                .append("device=").append(Build.DEVICE).append(" model=").append(Build.MODEL).append('\n')
                .append("android=").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT).append('\n')
                .append("firmware=").append(Build.VERSION.INCREMENTAL).append('\n')
                .append("milink=").append(version("com.milink.service")).append('\n')
                .append("manager=").append(version("org.lsposed.manager")).append("\n\n");
        try {
            String events = RelayDiagnostics.read(this);
            text.append(events.isEmpty() ? "No host events recorded. Check module enablement, scope, host restart and API 101 support; attach LSPosed logs.\n" : events);
        } catch (Exception error) { text.append("Cannot read diagnostic: ").append(error.getClass().getSimpleName()); }
        report = text.toString();
        output.setText(report);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1 || result != RESULT_OK || data == null || data.getData() == null) return;
        try (OutputStream stream = getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (stream == null) throw new java.io.IOException();
            stream.write(report.getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "诊断日志已导出", Toast.LENGTH_SHORT).show();
        } catch (Exception error) { Toast.makeText(this, "导出失败：" + error.getClass().getSimpleName(), Toast.LENGTH_SHORT).show(); }
    }
}
