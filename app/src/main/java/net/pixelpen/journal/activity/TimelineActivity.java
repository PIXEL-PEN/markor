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
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
