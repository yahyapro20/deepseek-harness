package com.dshmobile.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 设置：SD 映射、端口、镜像、日志、服务控制、重置。DeepSeek 风格。 */
public class SettingsActivity extends Activity {

    private Prefs prefs;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.of(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list != null) refresh();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.bgSoft(this));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list);
        setContentView(scroll);
        fillRows();
    }

    private void fillRows() {
        TextView pageTitle = Ui.title(this, "设置");
        LinearLayout.LayoutParams tlp = Ui.matchWrap();
        tlp.bottomMargin = Ui.dp(this, 4);
        list.addView(pageTitle, tlp);

        // ---- SD 卡映射 ----
        addHeader("SD 卡映射（容器 /mnt/sd）");
        LinearLayout sdCard = Ui.card(this);
        boolean allFiles = Environment.isExternalStorageManager();
        addRow(sdCard, "所有文件访问权限",
                allFiles ? "已授权 ✓" : "未授权 — 点这里去开启",
                v -> {
                    if (!Environment.isExternalStorageManager()) {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    }
                });
        sdCard.addView(Ui.divider(this));
        addRow(sdCard, "外置 SD 目录", sdDisplay(), v -> pickSdDir());
        sdCard.addView(Ui.divider(this));
        addRow(sdCard, "共享存储兜底", "/sdcard/dsh-shared → /mnt/shared（自动创建）", null);
        list.addView(sdCard, cardLp());

        // ---- 服务 ----
        addHeader("服务");
        LinearLayout svcCard = Ui.card(this);
        addRow(svcCard, "Web 端口", String.valueOf(prefs.getPort()),
                v -> editText("Web 端口", String.valueOf(prefs.getPort()), t -> {
                    try {
                        int p = Integer.parseInt(t.trim());
                        if (p > 0 && p < 65536) {
                            prefs.setPort(p);
                            toast("端口已保存，重启服务后生效");
                            refresh();
                        }
                    } catch (NumberFormatException ignored) {
                        toast("端口无效");
                    }
                }));
        svcCard.addView(Ui.divider(this));
        addRow(svcCard, "服务状态", HarnessService.isRunning() ? "运行中" : "已停止", null);
        list.addView(svcCard, cardLp());

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button toggle = Ui.primaryButton(this, HarnessService.isRunning() ? "停止服务" : "启动服务");
        toggle.setOnClickListener(v -> {
            if (HarnessService.isRunning()) {
                HarnessService.stopService(this);
            } else {
                HarnessService.startService(this);
            }
            list.postDelayed(this::refresh, 800);
        });
        LinearLayout.LayoutParams tbp = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        btnRow.addView(toggle, tbp);
        Button restart = Ui.outlineButton(this, "重启服务");
        restart.setOnClickListener(v -> {
            HarnessService.stopService(this);
            // 停止在独立线程执行（强杀兜底最长 ~3s），等旧容器确实死掉再启动，
            // 否则启动时 isRunning() 仍为 true，runLoop 不会被拉起，服务假死
            list.postDelayed(() -> {
                HarnessService.startService(this);
                refresh();
            }, 4000);
        });
        LinearLayout.LayoutParams rbp = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        rbp.leftMargin = Ui.dp(this, 12);
        btnRow.addView(restart, rbp);
        LinearLayout.LayoutParams brlp = Ui.matchWrap();
        brlp.topMargin = Ui.dp(this, 12);
        list.addView(btnRow, brlp);

        // ---- 容器 SSH ----
        addHeader("容器 SSH（随服务自启）");
        LinearLayout sshCard = Ui.card(this);
        addRow(sshCard, "连接方式",
                "本机终端/Termux：ssh dsh@127.0.0.1 -p " + prefs.getSshPort()
                        + "（普通用户，PATH 已带 node/npm；root 同密码也可登）；电脑：adb forward tcp:"
                        + prefs.getSshPort() + " tcp:" + prefs.getSshPort(),
                null);
        sshCard.addView(Ui.divider(this));
        addRow(sshCard, "用户名 / 密码", "dsh / " + prefs.getSshPassword() + "（点按复制密码）", v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ssh", prefs.getSshPassword()));
            toast("密码已复制");
        });
        sshCard.addView(Ui.divider(this));
        addRow(sshCard, "SSH 端口", String.valueOf(prefs.getSshPort()) + "（若与本机 Termux 冲突可改）",
                v -> editText("SSH 端口", String.valueOf(prefs.getSshPort()), t -> {
                    try {
                        int p = Integer.parseInt(t.trim());
                        if (p > 0 && p < 65536) {
                            prefs.setSshPort(p);
                            toast("SSH 端口已保存，重启服务后生效");
                            refresh();
                        }
                    } catch (NumberFormatException ignored) {
                        toast("端口无效");
                    }
                }));
        list.addView(sshCard, cardLp());

        // ---- 后台保活 ----
        // 划卡清任务被杀是 OEM 行为（荣耀/MagicOS 默认杀整进程），App 侧只能
        // 尽量降低被杀概率：电池优化白名单 + 引导用户开「自启动/允许后台活动」。
        addHeader("后台保活（防止划卡后服务被杀）");
        LinearLayout keepCard = Ui.card(this);
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        addRow(keepCard, "忽略电池优化",
                ignoring ? "已加入白名单 ✓" : "未加入 — 点这里去开启",
                v -> {
                    android.os.PowerManager p = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                    if (p != null && !p.isIgnoringBatteryOptimizations(getPackageName())) {
                        try {
                            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            toast("无法打开电池优化设置");
                        }
                    }
                });
        keepCard.addView(Ui.divider(this));
        addRow(keepCard, "自启动 / 允许后台活动",
                "荣耀等机型必须手动开启，点这里跳到应用详情：耗电详情/启动管理 → 允许自启动、允许后台活动",
                v -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        i.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception e) {
                        toast("无法打开应用详情页");
                    }
                });
        list.addView(keepCard, cardLp());

        // ---- 镜像 ----
        addHeader("下载镜像（下次安装生效）");
        LinearLayout mirrorCard = Ui.card(this);
        addRow(mirrorCard, "rootfs 地址", prefs.getRootfsUrl(),
                v -> editText("rootfs 地址", prefs.getRootfsUrl(), t -> {
                    prefs.setRootfsUrl(t.trim());
                    refresh();
                }));
        mirrorCard.addView(Ui.divider(this));
        addRow(mirrorCard, "Node.js 镜像", prefs.getNodeMirror(),
                v -> editText("Node.js 镜像", prefs.getNodeMirror(), t -> {
                    prefs.setNodeMirror(t.trim());
                    refresh();
                }));
        mirrorCard.addView(Ui.divider(this));
        addRow(mirrorCard, "npm registry", prefs.getNpmRegistry(),
                v -> editText("npm registry", prefs.getNpmRegistry(), t -> {
                    prefs.setNpmRegistry(t.trim());
                    refresh();
                }));
        list.addView(mirrorCard, cardLp());

        // ---- 维护 ----
        addHeader("维护");
        Button fmBtn = Ui.outlineButton(this, "文件管理器（传入/导出）");
        fmBtn.setOnClickListener(v -> startActivity(new Intent(this, FileManagerActivity.class)));
        list.addView(fmBtn, btnLp());
        Button termBtn = Ui.outlineButton(this, "打开容器终端");
        termBtn.setOnClickListener(v -> startActivity(new Intent(this, TerminalActivity.class)));
        list.addView(termBtn, btnLp());
        Button logBtn = Ui.outlineButton(this, "查看运行日志");
        logBtn.setOnClickListener(v -> showLog("dsh-web.log"));
        list.addView(logBtn, btnLp());
        Button installLogBtn = Ui.outlineButton(this, "查看安装日志");
        installLogBtn.setOnClickListener(v -> showLog("install.log"));
        list.addView(installLogBtn, btnLp());
        Button resetBtn = Ui.outlineButton(this, "重置容器（删除全部数据）");
        resetBtn.setTextColor(0xFFE54545);
        resetBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("重置容器")
                .setMessage("将删除 Ubuntu 容器及其中全部数据，下次启动重新安装。确定？")
                .setPositiveButton("重置", (d, w) -> {
                    HarnessService.stopService(this);
                    new Thread(() -> {
                        BootstrapInstaller.deleteRecursively(ProotRunner.baseDir(this));
                        prefs.setSetupDone(false);
                        runOnUiThread(() -> {
                            startActivity(new Intent(this, SetupActivity.class));
                            finish();
                        });
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show());
        list.addView(resetBtn, btnLp());
    }

    private String sdDisplay() {
        String sd = prefs.getSdPath();
        if (sd == null) return "未设置（仅使用 /mnt/shared 兜底）";
        return sd + (new File(sd).isDirectory() ? "" : "  （路径不存在！）");
    }

    private void pickSdDir() {
        if (!Environment.isExternalStorageManager()) {
            toast("请先开启「所有文件访问权限」");
            return;
        }
        List<String> options = new ArrayList<>();
        options.add("不映射（仅 /mnt/shared）");
        File[] vols = new File("/storage").listFiles();
        if (vols != null) {
            for (File v : vols) {
                if (v.isDirectory() && !v.getName().equals("emulated") && !v.getName().equals("self")) {
                    options.add(v.getAbsolutePath());
                }
            }
        }
        options.add("手动输入路径…");
        String[] items = options.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("选择外置 SD 目录")
                .setItems(items, (d, which) -> {
                    String sel = items[which];
                    if (which == 0) {
                        prefs.setSdPath(null);
                        refresh();
                    } else if (sel.startsWith("/storage")) {
                        String dir = sel + "/dsh";
                        //noinspection ResultOfMethodCallIgnored
                        new File(dir).mkdirs();
                        prefs.setSdPath(dir);
                        toast("已映射 " + dir + " → /mnt/sd，重启服务生效");
                        refresh();
                    } else {
                        editText("SD 目录完整路径",
                                prefs.getSdPath() == null ? "" : prefs.getSdPath(),
                                t -> {
                                    prefs.setSdPath(t.trim());
                                    refresh();
                                });
                    }
                })
                .show();
    }

    private void showLog(String name) {
        File f = new File(ProotRunner.baseDir(this), name);
        StringBuilder sb = new StringBuilder();
        if (f.isFile()) {
            try {
                byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
                int from = Math.max(0, all.length - 60_000);
                sb.append(new String(all, from, all.length - from));
            } catch (Exception e) {
                sb.append("读取失败: ").append(e.getMessage());
            }
        } else {
            sb.append("（暂无日志）");
        }
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int p = Ui.dp(this, 12);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void editText(String title, String current, OnText cb) {
        EditText et = new EditText(this);
        et.setText(current);
        et.setSingleLine(true);
        int p = Ui.dp(this, 16);
        et.setPadding(p, p / 2, p, p / 2);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("保存", (d, w) -> cb.accept(et.getText().toString()))
                .setNegativeButton("取消", null)
                .show();
    }

    private interface OnText {
        void accept(String t);
    }

    private void addHeader(String text) {
        TextView tv = Ui.sectionHeader(this, text);
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.topMargin = Ui.dp(this, 20);
        lp.bottomMargin = Ui.dp(this, 8);
        list.addView(tv, lp);
    }

    private void addRow(LinearLayout parent, String title, String subtitle, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        TextView t = Ui.body(this, title);
        row.addView(t);
        if (subtitle != null) {
            TextView s = Ui.hint(this, subtitle);
            s.setTextSize(12);
            row.addView(s);
        }
        if (click != null) {
            row.setOnClickListener(click);
        }
        parent.addView(row, Ui.matchWrap());
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        return lp;
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.topMargin = Ui.dp(this, 10);
        return lp;
    }

    private void refresh() {
        list.removeAllViews();
        fillRows();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
