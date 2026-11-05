/*#######################################################
 *
 *   Autumn - journal, archive, timeline
 *   Maintained 2026 by PIXEL-PEN
 *   License: Apache 2.0
 *
#########################################################*/
package net.pixelpen.journal.activity;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import net.pixelpen.journal.R;
import net.pixelpen.journal.model.ThumbLoader;

/**
 * Full-screen picture viewer, swipeable across one note's pictures.
 *
 * <p>Built rather than handed to an external gallery app on purpose: the archive hides its picture
 * folders with {@code .nomedia} so they stay out of every gallery on the device, which is the
 * whole point of keeping them in one place. A viewer that relied on those apps would be asking
 * them to show what it just told them to ignore.
 *
 * <p>Tap closes. Swipe moves between the pictures of the year that was tapped.
 */
public class ImageViewerActivity extends AutumnBaseActivity {

    /** String array of absolute paths. */
    public static final String EXTRA_PATHS = "paths";
    /** Index within {@link #EXTRA_PATHS} to open at. */
    public static final String EXTRA_START = "start";

    /** Longest edge decoded for the full view. Enough for any phone screen. */
    private static final int VIEW_EDGE = 2048;

    @Override
    protected void onPreCreate(@Nullable final Bundle savedInstanceState) {
        super.onPreCreate(savedInstanceState);
        // The base sets the app's dark-action-bar theme for every screen. A picture wants the
        // whole window, so this is reapplied afterwards rather than declared in the manifest,
        // where the base would simply overwrite it.
        setTheme(R.style.AppTheme_Viewer);
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final String[] paths = getIntent().getStringArrayExtra(EXTRA_PATHS);
        if (paths == null || paths.length == 0) {
            finish();
            return;
        }
        final List<File> files = new ArrayList<>();
        for (final String p : paths) {
            final File f = new File(p);
            if (f.isFile()) {
                files.add(f);
            }
        }
        if (files.isEmpty()) {
            finish();
            return;
        }

        final ViewPager2 pager = new ViewPager2(this);
        pager.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pager.setBackgroundColor(Color.BLACK);
        pager.setAdapter(new PageAdapter(files));
        setContentView(pager);

        final int start = getIntent().getIntExtra(EXTRA_START, 0);
        if (start > 0 && start < files.size()) {
            pager.setCurrentItem(start, false);
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
    }

    private final class PageAdapter extends RecyclerView.Adapter<PageHolder> {
        private final List<File> _files;

        PageAdapter(final List<File> files) {
            _files = files;
        }

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
            final ImageView iv = new ImageView(parent.getContext());
            iv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setOnClickListener(v -> finish());
            return new PageHolder(iv);
        }

        @Override
        public void onBindViewHolder(@NonNull final PageHolder holder, final int position) {
            ThumbLoader.load((ImageView) holder.itemView, _files.get(position), VIEW_EDGE);
        }

        @Override
        public int getItemCount() {
            return _files.size();
        }
    }

    private static final class PageHolder extends RecyclerView.ViewHolder {
        PageHolder(final View v) {
            super(v);
        }
    }
}
