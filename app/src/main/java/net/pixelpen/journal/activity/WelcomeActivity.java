/*#######################################################
 *
 *   Autumn
 *   Copyright (C) 2026 PIXEL-PEN
 *   Licensed under the GNU General Public License v3.0
 *
#########################################################*/
package net.pixelpen.journal.activity;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import net.gsantner.opoc.util.GsContextUtils;
import net.pixelpen.journal.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The welcome cards: four pages shown on first launch, ending with the storage request.
 *
 * <p>They are shown until the run is finished (the last card's button) or Don't show again is
 * chosen. Skip only skips this time; the cards come back on the next launch. Every route ends
 * on the last card when storage access is still missing, so the request is always explained
 * before it is made.
 *
 * <p>The pictures are ordinary views and the animations plain property animators, which the
 * system's animation scale already governs: with animations off they simply stand still.
 */
public class WelcomeActivity extends AutumnBaseActivity {

    private static final String PREFS = "welcome";
    private static final String KEY_DONE = "done";

    private static final int[] PAGES = {
            R.layout.welcome__page_steps,
            R.layout.welcome__page_quicknote,
            R.layout.welcome__page_timeline,
            R.layout.welcome__page_privacy,
    };

    /**
     * Once per process. Skip does not mark the cards done, and MainActivity resumes when they
     * close; without this it would open them again straight away.
     */
    private static boolean _shownThisRun = false;

    private final AtomicBoolean _responseProcessed = new AtomicBoolean(false);
    private final List<Animator> _animators = new ArrayList<>();
    private ViewPager _pager;
    private LinearLayout _dots;
    private View _skip;
    private View _navRow;
    private View _final;
    private TextView _allow;

    /**
     * Opens the cards if they are due. True when they were opened.
     *
     * <p>Debug builds show them on every launch, done or not, so they can be checked while
     * they are being worked on. Release builds keep to the done flag.
     */
    public static boolean showIfDue(final Activity from) {
        if (_shownThisRun || (!net.pixelpen.journal.BuildConfig.DEBUG && isDone(from))) {
            return false;
        }
        _shownThisRun = true;
        from.startActivity(new Intent(from, WelcomeActivity.class));
        return true;
    }

    private static boolean isDone(final Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false);
    }

    private void markDone() {
        final SharedPreferences.Editor e = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putBoolean(KEY_DONE, true);
        e.apply();
    }

    @Override
    public void onCreate(final Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.welcome__activity);

        _pager = findViewById(R.id.welcome__pager);
        _dots = findViewById(R.id.welcome__dots);
        _skip = findViewById(R.id.welcome__skip);
        _navRow = findViewById(R.id.welcome__nav_row);
        _final = findViewById(R.id.welcome__final);
        _allow = findViewById(R.id.welcome__allow);

        for (int i = 0; i < PAGES.length; i++) {
            final View dot = new View(this);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(8), dp(8));
            lp.setMarginStart(dp(4));
            lp.setMarginEnd(dp(4));
            _dots.addView(dot, lp);
        }

        _pager.setOffscreenPageLimit(PAGES.length);
        _pager.setAdapter(new Pages());
        _pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(final int position) {
                showControlsFor(position);
            }
        });

        _skip.setOnClickListener(v -> toLastOrClose());
        findViewById(R.id.welcome__dont_show).setOnClickListener(v -> {
            markDone();
            toLastOrClose();
        });
        findViewById(R.id.welcome__next).setOnClickListener(v ->
                _pager.setCurrentItem(_pager.getCurrentItem() + 1, true));
        _allow.setOnClickListener(v -> {
            if (hasStorage()) {
                markDone();
                finish();
            } else {
                _responseProcessed.set(false);
                GsContextUtils.instance.requestExternalStoragePermission(this);
            }
        });
        // MainActivity asks again on resume, with the plain dialog, if access is still missing.
        findViewById(R.id.welcome__not_now).setOnClickListener(v -> finish());

        showControlsFor(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Access may have been granted in system settings meanwhile.
        _allow.setText(hasStorage() ? R.string.welcome_get_started : R.string.welcome_allow);
    }

    @Override
    protected void onDestroy() {
        for (final Animator a : _animators) {
            a.cancel();
        }
        _animators.clear();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (_pager != null && _pager.getCurrentItem() > 0) {
            _pager.setCurrentItem(_pager.getCurrentItem() - 1, true);
        } else {
            super.onBackPressed();
        }
    }

    private boolean hasStorage() {
        return GsContextUtils.instance.checkExternalStoragePermission(this);
    }

    /** With storage already granted there is nothing left to say; otherwise the last card. */
    private void toLastOrClose() {
        if (hasStorage()) {
            finish();
        } else {
            _pager.setCurrentItem(PAGES.length - 1, true);
        }
    }

    private void showControlsFor(final int position) {
        final boolean last = position == PAGES.length - 1;
        _skip.setVisibility(last ? View.INVISIBLE : View.VISIBLE);
        _navRow.setVisibility(last ? View.GONE : View.VISIBLE);
        _final.setVisibility(last ? View.VISIBLE : View.GONE);
        for (int i = 0; i < _dots.getChildCount(); i++) {
            final View dot = _dots.getChildAt(i);
            final ViewGroup.LayoutParams lp = dot.getLayoutParams();
            lp.width = dp(i == position ? 22 : 8);
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(i == position ? R.drawable.welcome_dot_on : R.drawable.welcome_dot_off);
        }
    }

    // ------------------------------------------------------------------ permission

    private void processPermissionState() {
        // Both callbacks can fire for one request; act once.
        if (_responseProcessed.getAndSet(true)) {
            return;
        }
        if (hasStorage()) {
            markDone();
            finish();
        } else {
            Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, @NonNull final String[] permissions,
                                           @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        processPermissionState();
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        processPermissionState();
    }

    // ------------------------------------------------------------------ pages

    private final class Pages extends PagerAdapter {
        @Override
        public int getCount() {
            return PAGES.length;
        }

        @Override
        public boolean isViewFromObject(@NonNull final View view, @NonNull final Object object) {
            return view == object;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull final ViewGroup container, final int position) {
            final View page = getLayoutInflater().inflate(PAGES[position], container, false);
            container.addView(page);
            animate(page);
            return page;
        }

        @Override
        public void destroyItem(@NonNull final ViewGroup container, final int position,
                                @NonNull final Object object) {
            container.removeView((View) object);
        }
    }

    /** The small loops on each card. A view a page does not have is simply skipped. */
    private void animate(final View page) {
        final View cursor = page.findViewById(R.id.welcome__cursor);
        if (cursor != null) {
            final ObjectAnimator a = ObjectAnimator.ofFloat(cursor, View.ALPHA, 1f, 1f, 0f, 0f);
            a.setDuration(1000);
            a.setRepeatCount(ValueAnimator.INFINITE);
            start(a);
        }

        final View treeNew = page.findViewById(R.id.welcome__tree_new);
        if (treeNew != null) {
            final ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(treeNew,
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 0f, 1f, 1f, 1f, 0f),
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_X, -dp(10), -dp(10), 0f, 0f, 0f, 0f));
            a.setDuration(4000);
            a.setRepeatCount(ValueAnimator.INFINITE);
            start(a);
        }

        final View ring = page.findViewById(R.id.welcome__ring);
        if (ring != null) {
            final ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(ring,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 0.6f, 1.9f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.6f, 1.9f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f));
            a.setDuration(1800);
            a.setRepeatCount(ValueAnimator.INFINITE);
            start(a);
        }

        final View popup = page.findViewById(R.id.welcome__popup);
        if (popup != null) {
            final ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(popup,
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 0f, 1f, 1f, 1f, 1f, 0f),
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, dp(16), dp(16), 0f, 0f, 0f, 0f, -dp(6)));
            a.setDuration(4500);
            a.setRepeatCount(ValueAnimator.INFINITE);
            start(a);
        }

        final View years = page.findViewById(R.id.welcome__timeline_years);
        if (years instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) years;
            for (int i = 0; i < g.getChildCount(); i++) {
                final ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(g.getChildAt(i),
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f, 1f, 1f, 1f),
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_X, -dp(8), 0f, 0f, 0f, 0f));
                a.setDuration(6000);
                a.setStartDelay(i * 500L);
                a.setRepeatCount(ValueAnimator.INFINITE);
                start(a);
            }
        }

        final int[] files = {R.id.welcome__file1, R.id.welcome__file2, R.id.welcome__file3};
        for (int i = 0; i < files.length; i++) {
            final View f = page.findViewById(files[i]);
            if (f == null) {
                continue;
            }
            final ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(f,
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f, 1f, 1f, 1f),
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, -dp(14), 0f, 0f, 0f, 0f));
            a.setDuration(5000);
            a.setStartDelay(i * 400L);
            a.setRepeatCount(ValueAnimator.INFINITE);
            start(a);
        }
    }

    private void start(final Animator a) {
        _animators.add(a);
        a.start();
    }

    private int dp(final int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
