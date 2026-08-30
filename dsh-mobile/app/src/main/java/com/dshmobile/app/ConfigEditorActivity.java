package com.dshmobile.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Native editor for dsh configuration file (/home/dsh/.dsh/settings.yaml inside the container).
 * The "Open configuration file" button in dsh web desktop calls xdg-open/editor, which inevitably fails in the container;
 * the web side intercepts the button click via inject.js and uses the JS bridge to open this page.
 * dsh-settings-file uses chokidar to watch this file, automatically hot-reloading after save without restarting the service.
 */
public class ConfigEditorActivity extends Activity {

    private EditText editor;
    private TextView status;
    private File configFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        configFile = new File(ProotRunner.homeDir(this), ".dsh/settings.yaml");
        buildUi();
        loadFile();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Ui.bg(this));

        TextView title = Ui.title(this, "Configuration File");
        root.addView(title);

        TextView path = Ui.hint(this, configFile.getAbsolutePath());
        LinearLayout.LayoutParams plp = Ui.matchWrap();
        plp.topMargin = Ui.dp(this, 4);
        root.addView(path, plp);

        editor = new EditText(this);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(13);
        editor.setTextColor(Ui.text(this));
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setHorizontallyScrolling(true);
        editor.setBackgroundColor(Ui.bgSoft(this));
        int epad = Ui.dp(this, 12);
        editor.setPadding(epad, epad, epad, epad);
        ScrollView sv = new ScrollView(this);
        sv.addView(editor, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = Ui.dp(this, 12);
        root.addView(sv, slp);

        status = Ui.hint(this, "");
        LinearLayout.LayoutParams stlp = Ui.matchWrap();
        stlp.topMargin = Ui.dp(this, 8);
        root.addView(status, stlp);

        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = Ui.matchWrap();
        blp.topMargin = Ui.dp(this, 8);
        Button cancel = Ui.outlineButton(this, "Back");
        cancel.setOnClickListener(v -> finish());
        btns.addView(cancel);
        Button save = Ui.primaryButton(this, "Save");
        LinearLayout.LayoutParams s2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48));
        s2.leftMargin = Ui.dp(this, 16);
        save.setLayoutParams(s2);
        save.setOnClickListener(v -> saveFile());
        btns.addView(save);
        root.addView(btns, blp);

        setContentView(root);
    }

    private void loadFile() {
        if (!configFile.isFile()) {
            // Provide an empty document with comments when file does not exist (dsh's prepareDocument also provides empty files)
            editor.setText("# dsh settings\n# See settings dialog for namespace configuration items; dsh auto hot-reloads after save.\n");
            status.setText("File does not exist yet, will be created on save");
            return;
        }
        try {
            FileInputStream in = new FileInputStream(configFile);
            byte[] buf = new byte[(int) Math.min(configFile.length(), 1024 * 1024)];
            int n = in.read(buf);
            in.close();
            editor.setText(new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8));
        } catch (IOException e) {
            status.setText("Read failed: " + e.getMessage());
        }
    }

    /** Write to temporary file then rename (atomic replacement) to avoid dsh's watcher reading partial content. */
    private void saveFile() {
        try {
            File dir = configFile.getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(configFile.getParentFile(), "settings.yaml.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(editor.getText().toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
            out.close();
            if (!tmp.renameTo(configFile)) {
                throw new IOException("rename failed");
            }
            status.setText("Saved (dsh auto hot-loads, no restart needed)");
            Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            status.setText("Save failed: " + e.getMessage());
        }
    }
}
