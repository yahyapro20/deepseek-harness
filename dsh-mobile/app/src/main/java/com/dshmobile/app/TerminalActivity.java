package com.dshmobile.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Container terminal: runs a persistent bash in proot Ubuntu (pipe I/O, non-PTY).
 * cd/environment variables etc. are maintained within the session; if a command hangs, click "Restart" to kill the entire shell and start over.
 */
public class TerminalActivity extends Activity {

    private static final int BG = Color.parseColor("#0D1117");
    private static final int PANEL = Color.parseColor("#161B22");
    private static final int FG = Color.parseColor("#E6EDF3");
    private static final int FG_DIM = Color.parseColor("#8B949E");
    private static final int ACCENT = Color.parseColor("#58A6FF");
    private static final int DIVIDER = Color.parseColor("#30363D");
    /** Output buffer limit: discard early content when exceeded to prevent long tasks from consuming memory. */
    private static final int MAX_BUFFER = 200_000;
    private static final int KEEP_BUFFER = 150_000;

    private final StringBuilder buffer = new StringBuilder();
    private final Object lock = new Object();
    private Handler handler;
    private ScrollView scroll;
    private TextView output;
    private EditText input;
    private Process shell;
    private OutputStream stdin;
    private boolean flushPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler = new Handler(Looper.getMainLooper());
        buildUi();
        startShell();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // Top bar: Title + Clear/Restart
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int bp = Ui.dp(this, 8);
        bar.setPadding(bp, bp, bp, bp);
        TextView title = new TextView(this);
        title.setText("Container Terminal");
        title.setTextColor(FG);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(action("Clear", v -> {
            synchronized (lock) {
                buffer.setLength(0);
            }
            output.setText("");
        }));
        bar.addView(action("Restart", v -> startShell()));
        root.addView(bar);
        View div = new View(this);
        div.setBackgroundColor(DIVIDER);
        root.addView(div, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 0.5f)));

        // Output area
        output = new TextView(this);
        output.setTextColor(FG);
        output.setTextSize(12);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        int op = Ui.dp(this, 10);
        output.setPadding(op, op, op, op);
        scroll = new ScrollView(this);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Input line
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(PANEL);
        int rp = Ui.dp(this, 8);
        row.setPadding(rp, rp, rp, rp);
        input = new EditText(this);
        input.setTextColor(FG);
        input.setHintTextColor(FG_DIM);
        input.setHint("Enter command, press Enter to execute");
        input.setTextSize(13);
        input.setTypeface(Typeface.MONOSPACE);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setBackground(null);
        input.setOnEditorActionListener((v, actionId, ev) -> {
            send();
            return true;
        });
        row.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(action("Execute", v -> send()));
        root.addView(row);

        setContentView(root);
    }

    private TextView action(String text, View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(ACCENT);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        int h = Ui.dp(this, 12);
        int v = Ui.dp(this, 6);
        tv.setPadding(h, v, h, v);
        tv.setOnClickListener(click);
        return tv;
    }

    private void startShell() {
        stopShell();
        if (!ProotRunner.prootBin(this).isFile() || !ProotRunner.rootfsDir(this).isDirectory()) {
            append("Container not installed yet, please complete initialization first.\n");
            return;
        }
        try {
            shell = ProotRunner.execPiped(this, Arrays.asList("/bin/bash", "--noprofile", "--norc"));
            stdin = shell.getOutputStream();
            Process p = shell;
            new Thread(() -> readLoop(p), "dsh-term-read").start();
            append("Connected to container shell (working directory /home/dsh; click 'Restart' to interrupt if command hangs)\n");
        } catch (IOException e) {
            append("Failed to start shell: " + e.getMessage() + "\n");
        }
    }

    private void stopShell() {
        if (shell != null) {
            // proot with --kill-on-exit, destroy kills all processes in the container tree
            shell.destroy();
            shell = null;
        }
        stdin = null;
    }

    private void readLoop(Process p) {
        try (InputStreamReader r =
                     new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) {
                append(new String(buf, 0, n));
            }
        } catch (IOException ignored) {
        }
        // Only prompt if current session exits naturally (old session replaced by "Restart" does not show prompt)
        if (p == shell) {
            append("\n[Session ended, click 'Restart' to reconnect]\n");
        }
    }

    private void send() {
        String cmd = input.getText().toString();
        input.setText("");
        OutputStream out = stdin;
        if (shell == null || out == null || !shell.isAlive()) {
            append("(shell not connected, click 'Restart' in top right)\n");
            return;
        }
        if (!cmd.isEmpty()) {
            append("$ " + cmd + "\n");
        }
        new Thread(() -> {
            try {
                out.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                handler.post(() -> append("[Write failed: " + e.getMessage() + "]\n"));
            }
        }, "dsh-term-write").start();
    }

    private void append(String s) {
        synchronized (lock) {
            buffer.append(s);
            if (buffer.length() > MAX_BUFFER) {
                buffer.delete(0, buffer.length() - KEEP_BUFFER);
                buffer.insert(0, "…(early output truncated)\n");
            }
        }
        scheduleFlush();
    }

    private void scheduleFlush() {
        if (flushPending) return;
        flushPending = true;
        handler.post(() -> {
            flushPending = false;
            synchronized (lock) {
                output.setText(buffer.toString());
            }
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    protected void onDestroy() {
        stopShell();
        super.onDestroy();
    }
}
