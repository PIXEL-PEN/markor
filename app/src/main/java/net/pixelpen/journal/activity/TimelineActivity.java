package net.pixelpen.journal.activity;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.pixelpen.journal.R;

/**
 * Hosts the life timeline.
 *
 * <p>It was a fifth tab on the bottom bar. Five tabs crowded the four used daily, and the timeline
 * is not one of them: it is somewhere you go deliberately and stay for a while, not somewhere you
 * flick to between capturing notes. So it lives behind the overflow menu, and needs an activity of
 * its own because the pager's page count is the bottom navigation's menu size.
 */
public class TimelineActivity extends AutumnBaseActivity {

    private TimelineFragment _fragment;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_timeline);

        setSupportActionBar(findViewById(R.id.timeline_activity_toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.timeline);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        _fragment = (TimelineFragment) getSupportFragmentManager()
                .findFragmentByTag(TimelineFragment.FRAGMENT_TAG);
        if (_fragment == null) {
            _fragment = TimelineFragment.newInstance();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.timeline_activity_container, _fragment,
                            TimelineFragment.FRAGMENT_TAG)
                    .commit();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the year editor, where the writing actually happens.
        if (_fragment != null) {
            _fragment.reload();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        final MenuItem add = menu.add(0, R.id.action_timeline_add_years, 0,
                R.string.timeline_add_years).setIcon(R.drawable.ic_add_white_24dp);
        add.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        // Overflow, not the bar: exporting is occasional, adding years is not.
        menu.add(0, R.id.action_timeline_export, 1, R.string.export_html)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == R.id.action_timeline_add_years) {
            if (_fragment != null) {
                _fragment.promptAddYears();
            }
            return true;
        }
        if (item.getItemId() == R.id.action_timeline_export) {
            runHtmlExport();
            return true;
        }
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Writes the timeline to one self-contained HTML file and hands it to the share sheet.
     *
     * <p>Off the main thread: it reads every year note and re-encodes every picture in the
     * timeline, which on a long life is seconds rather than milliseconds.
     */
    private void runHtmlExport() {
        final java.io.File home = _appSettings.getNotebookDirectory();
        if (home == null || !home.isDirectory()) {
            android.widget.Toast.makeText(this, R.string.export_empty,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        final androidx.appcompat.app.AlertDialog dialog =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setMessage(R.string.export_working)
                        .setCancelable(false)
                        .show();

        new Thread(() -> {
            java.io.File page = null;
            try {
                page = net.pixelpen.journal.model.HtmlExport.exportTimeline(this, home);
            } catch (final Exception e) {
                page = null;
            }
            final java.io.File done = page;
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                dialog.dismiss();
                if (done == null) {
                    android.widget.Toast.makeText(this, R.string.export_failed,
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                _cu.shareStream(this, done, "text/html");
            });
        }).start();
    }

    @Override
    public void onBackPressed() {
        // The fragment closes an open year first; only then does back leave the screen.
        if (_fragment != null && _fragment.onBackPressed()) {
            return;
        }
        super.onBackPressed();
    }
}
