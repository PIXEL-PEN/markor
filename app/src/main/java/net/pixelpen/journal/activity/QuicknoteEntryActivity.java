/*#######################################################
 *
 *   Autumn
 *   Copyright (C) 2026 PIXEL-PEN
 *   Licensed under the GNU General Public License v3.0
 *
#########################################################*/
package net.pixelpen.journal.activity;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
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
 * <p>It opens exactly over the widget and is drawn like it (quicknote_entry.xml mirrors
 * widget_quicknote.xml), with no dim and no frame, so the bar seems to become the field rather
 * than a second layer appearing. The launcher says where the widget is: a widget tap carries
 * its on-screen bounds in {@link android.content.Intent#getSourceBounds()}. Without them (a
 * launcher that does not supply them) the box opens across the top of the screen instead.
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
        placeOverWidget();
        _text = findViewById(R.id.qn_entry_text);
        _text.requestFocus();
        findViewById(R.id.qn_entry_save).setOnClickListener(v -> save());
    }

    private void placeOverWidget() {
        final Window w = getWindow();
        if (w == null) {
            return;
        }
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        final WindowManager.LayoutParams lp = w.getAttributes();
        lp.dimAmount = 0f;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        final Rect at = getIntent() != null ? getIntent().getSourceBounds() : null;
        if (at != null && at.width() > 0) {
            // Screen coordinates, the same frame the launcher measured the widget in.
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = at.left;
            lp.width = at.width();
            // The bar is centred in its cell; line the box's first row up with it.
            final int bar = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 52,
                    getResources().getDisplayMetrics()));
            lp.y = at.top + Math.max(0, (at.height() - bar) / 2);
        } else {
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        w.setAttributes(lp);
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
