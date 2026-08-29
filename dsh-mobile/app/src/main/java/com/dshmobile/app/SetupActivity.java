package com.dshmobile.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** 首启安装向导：DeepSeek 风格，下载/解压/安装进度与日志。 */
public class SetupActivity extends Activity implements BootstrapInstaller.Listener {

    /** 安装线程全局唯一标志：旋转屏幕/系统重建 Activity 会重跑 onCreate，
     *  没有它就会出现两个安装线程并发写同一 rootfs（issue #6）。 */
    private static volatile boolean installRunning;

    private ProgressBar progress;
    private TextView stageText;
    private TextView logView;
    private Button actionBtn;
    private Handler handler;
    private BootstrapInstaller installer;
    private Thread worker;
    private boolean done;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler = new Handler(Looper.getMainLooper());
        buildUi();
        startInstall();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.bgSoft(this));
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, pad, pad, pad);

        // 顶部标题区
        LinearLayout header = Ui.card(this);
        TextView title = Ui.title(this, "初始化 Ubuntu 容器");
        header.addView(title);
        TextView hint = Ui.hint(this,
                "首次启动需要联网下载约 400MB（Ubuntu + 编译工具链 + Node.js + DeepSeek Harness），请保持网络畅通。");
        LinearLayout.LayoutParams hlp = Ui.matchWrap();
        hlp.topMargin = Ui.dp(this, 8);
        header.addView(hint, hlp);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(Ui.PRIMARY));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.border(this)));
        LinearLayout.LayoutParams plp = Ui.matchWrap();
        plp.topMargin = Ui.dp(this, 16);
        header.addView(progress, plp);

        stageText = new TextView(this);
        stageText.setTextColor(Ui.PRIMARY);
        stageText.setTextSize(14);
        stageText.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams slp = Ui.matchWrap();
        slp.topMargin = Ui.dp(this, 8);
        header.addView(stageText, slp);

        root.addView(header, Ui.matchWrap());

        // 日志区
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(Ui.softBg(this));
        int lp2 = Ui.dp(this, 12);
        scroll.setPadding(lp2, lp2, lp2, lp2);
        logView = new TextView(this);
        logView.setTextColor(Ui.textSecondary(this));
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setMovementMethod(new ScrollingMovementMethod());
        scroll.addView(logView);
        LinearLayout.LayoutParams llp = Ui.matchWrap();
        llp.topMargin = Ui.dp(this, 16);
        llp.weight = 1;
        llp.height = 0;
        root.addView(scroll, llp);
        logView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) ->
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)));

        actionBtn = Ui.primaryButton(this, "取消");
        actionBtn.setOnClickListener(v -> {
            if (done) {
                startActivity(new Intent(this, MainActivity.class));
                finish();
            } else {
                if (installer != null) installer.cancel();
                if (worker != null) worker.interrupt();
                finish();
            }
        });
        LinearLayout.LayoutParams blp = Ui.matchWrap();
        blp.topMargin = Ui.dp(this, 16);
        root.addView(actionBtn, blp);

        setContentView(root);
    }

    private void startInstall() {
        if (installRunning) {
            // 已有安装线程在跑（重建前的实例仍在工作）：本实例退出，
            // 安装在后台完成，下次启动按 setupDone 自动进主界面
            finish();
            return;
        }
        installRunning = true;
        installer = new BootstrapInstaller(this, this);
        worker = new Thread(() -> {
            try {
                installer.run();
            } finally {
                installRunning = false;
            }
        }, "dsh-bootstrap");
        worker.start();
    }

    @Override
    public void onStage(String stage, int percent) {
        handler.post(() -> {
            progress.setProgress(percent, true);
            if (stage != null) {
                stageText.setText(stage + "  " + percent + "%");
            } else {
                String t = stageText.getText().toString();
                int i = t.lastIndexOf(' ');
                stageText.setText((i > 0 ? t.substring(0, i) : t) + " " + percent + "%");
            }
        });
    }

    @Override
    public void onLog(String line) {
        handler.post(() -> logView.append(line + "\n"));
    }

    @Override
    public void onDone(boolean success, String error) {
        handler.post(() -> {
            done = true;
            if (success) {
                stageText.setText("安装完成 ✓");
                stageText.setTextColor(Ui.PRIMARY);
                actionBtn.setText("开始使用");
            } else {
                stageText.setText("安装失败：" + error);
                stageText.setTextColor(0xFFE54545);
                actionBtn.setText("重试");
                actionBtn.setOnClickListener(v -> {
                    logView.setText("");
                    progress.setProgress(0);
                    stageText.setTextColor(Ui.PRIMARY);
                    actionBtn.setText("取消");
                    actionBtn.setOnClickListener(x -> finish());
                    startInstall();
                });
            }
        });
    }
}
