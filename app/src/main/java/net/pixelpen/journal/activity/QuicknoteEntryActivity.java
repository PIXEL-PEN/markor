/*#######################################################
 *
 *   Autumn
 *   Copyright (C) 2026 PIXEL-PEN
 *   Licensed under the GNU General Public License v3.0
 *
#########################################################*/
package net.pixelpen.journal.activity;

import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import net.gsantner.opoc.util.GsContextUtils;
import net.pixelpen.journal.ApplicationObject;
import net.pixelpen.journal.R;
import net.pixelpen.journal.model.Quicknote;

import java.io.File;
import java.util.Calendar;

/**
 * The box the Quicknote widget opens: a field and the Autumn logo, over the home screen.
 *
 * <p>The logo saves to this month's Quicknotes file, the same file the bottom-bar Quicknote
 * writes, stamped now, and closes. Back or a tap outside closes without saving.
 *
 * <p>An {@link AppCompatActivity}, not {@code AutumnBaseActivity}: the base activity sets the
 * full-screen app theme, which would replace this dialog theme. The forced night mode is
 * already set in {@code ApplicationObject}, so the box follows the app's theme choice anyway.
 */
public class QuicknoteEntryActivity extends AppCompatActivity {

    private EditText _text;

    @Override
    protected void onCreate(final Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.quicknote_entry);
        // A dialog window is only as wide as its content by default; a field wants the width.
        if (getWindow() != null) {
            getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        _text = findViewById(R.id.qn_entry_text);
        _text.requestFocus();
        findViewById(R.id.qn_entry_save).setOnClickListener(v -> save());
    }

    private void save() {
        final String text = _text.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.qn_widget_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!GsContextUtils.instance.checkExternalStoragePermission(this)) {
            // The text stays in the field; nothing is lost by saying so.
            Toast.makeText(this, R.string.qn_widget_no_storage, Toast.LENGTH_LONG).show();
            return;
        }
        final File home = ApplicationObject.settings().getNotebookDirectory();
        final File written = Quicknote.write(home, Calendar.getInstance(), text);
        if (written == null) {
            Toast.makeText(this, R.string.qn_widget_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.quicknote_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
