package net.pixelpen.journal.activity;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.pixelpen.journal.R;
import net.pixelpen.journal.model.Timeline;
import net.pixelpen.journal.util.NoteTimestamp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Editing one year of the life timeline.
 *
 * <p>Twelve fixed months down the left and a field beside each. This exists because the months
 * cannot be part of the text: seeded into the markdown they were editable, so the cursor could
 * land in one and a month could be renamed or deleted by accident while typing beside it, and a
 * scaffold that the act of using it can destroy is not a scaffold.
 *
 * <p>The file on disk is still plain markdown, and still readable and editable as such. This
 * screen is a way of typing into it that cannot damage its structure, not a different format.
 */
public class YearEntryActivity extends AutumnBaseActivity {

    public static final String EXTRA_FILE = "YEAR_ENTRY_FILE";
    public static final String EXTRA_YEAR = "YEAR_ENTRY_YEAR";

    /** Entries that named no month, kept so editing a year cannot quietly discard them. */
    private static final int UNDATED = 12;

    private static final int REQ_PICK_IMAGE = 7301;

    private final EditText[] _fields = new EditText[13];
    /**
     * The year's pictures, in the order they appear in the note.
     *
     * <p>Held apart from the month fields on purpose. They used to be inserted into whichever
     * field had the cursor, which put a markdown link in the middle of the text being typed and
     * implied the picture belonged to that month -- which nothing records and the timeline does
     * not show. Pictures belong to the year.
     */
    private final List<File> _images = new ArrayList<>();
    private LinearLayout _shots;
    private File _file;
    private int _year;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_year_entry);

        _file = (File) getIntent().getSerializableExtra(EXTRA_FILE);
        _year = getIntent().getIntExtra(EXTRA_YEAR, Calendar.getInstance().get(Calendar.YEAR));
        if (_file == null) {
            finish();
            return;
        }

        final androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.year_entry_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(String.valueOf(_year));
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        _shots = findViewById(R.id.year_entry_shots);

        final LinearLayout container = findViewById(R.id.year_entry_months);
        final LayoutInflater inflater = LayoutInflater.from(this);
        // readExisting fills _images as it goes, so the strip can only be built after it.
        final List<List<String>> byMonth = readExisting();
        buildShots();

        for (int m = 0; m <= UNDATED; m++) {
            // The undated row only appears when there is something in it. Offering it empty would
            // invite writing into it, and an entry with no month is a thing to be resolved rather
            // than somewhere to file things on purpose.
            if (m == UNDATED && byMonth.get(m).isEmpty()) {
                continue;
            }
            final View row = inflater.inflate(R.layout.item_year_entry_month, container, false);
            final TextView label = row.findViewById(R.id.entry_month_label);
            final EditText field = row.findViewById(R.id.entry_month_text);
            label.setText(m == UNDATED
                    ? getString(R.string.timeline_undated) : Timeline.MONTH_SHORT[m]);
            field.setText(join(byMonth.get(m)));
            _fields[m] = field;
            container.addView(row);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        final MenuItem save = menu.add(0, R.id.action_save, 0, R.string.save)
                .setIcon(R.drawable.ic_save_black_24dp);
        save.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        // The icon is a black vector and the bar is dark, so it has to be tinted rather than left
        // to the theme. Near-white with a hint of the bar's own blue in it, so it sits on the
        // toolbar instead of punching a hole in it.
        tint(save);

        final MenuItem image = menu.add(0, R.id.action_add_image, 1, R.string.year_entry_add_image)
                .setIcon(R.drawable.ic_image_black_24dp);
        image.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        tint(image);
        return true;
    }

    /** Black vectors on a dark bar have to be tinted; the theme does not do it for coded items. */
    private static void tint(final MenuItem item) {
        final android.graphics.drawable.Drawable icon = item.getIcon();
        if (icon != null) {
            icon.mutate().setColorFilter(new android.graphics.PorterDuffColorFilter(
                    0xFFE3ECEE, android.graphics.PorterDuff.Mode.SRC_IN));
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == R.id.action_save) {
            if (save()) {
                finish();
            }
            return true;
        }
        if (item.getItemId() == R.id.action_add_image) {
            pickImage();
            return true;
        }
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Saves on the way out.
     *
     * <p>Leaving without an explicit save is the normal way to leave a screen on Android, and
     * losing an evening's recollection because of it would be the worst failure this app could
     * have.
     */
    @Override
    public void onBackPressed() {
        save();
        super.onBackPressed();
    }

    /**
     * Choose a picture for this year.
     *
     * <p>No month is asked for. The timeline shows a year's pictures as one strip and keeps no
     * month against them, so asking would be collecting an answer nothing reads and nothing
     * displays -- and getting it wrong would be worse, since nothing about a misfiled picture
     * says that it is misfiled.
     */
    private void pickImage() {
        final android.content.Intent pick =
                new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        try {
            startActivityForResult(pick, REQ_PICK_IMAGE);
        } catch (final Exception e) {
            Toast.makeText(this, R.string.error_could_not_open_file, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
                                    @Nullable final android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        importAndInsert(data.getData());
    }

    /**
     * Copy the picture into the archive and write a link to it where the cursor was.
     *
     * <p>Copied rather than referenced: a link to wherever the gallery keeps it would break the
     * first time that photo was tidied up, and an archive meant to last decades cannot depend on
     * files it does not own.
     */
    private void importAndInsert(final android.net.Uri source) {
        final File home = net.pixelpen.journal.ApplicationObject.settings().getNotebookDirectory();
        final String name = displayNameOf(source);
        new Thread(() -> {
            File stored = null;
            try {
                stored = net.pixelpen.journal.model.ArchiveImages.importImage(
                        getContentResolver(), source, home, _year, name);
            } catch (final Exception e) {
                stored = null;
            }
            final File written = stored;
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (written == null) {
                    Toast.makeText(this, R.string.year_entry_image_failed,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                _images.add(written);
                buildShots();
            });
        }).start();
    }

    /** The picked file's own name, for the stored copy to carry a trace of. */
    private String displayNameOf(final android.net.Uri uri) {
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                final int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    return c.getString(i);
                }
            }
        } catch (final Exception ignored) {
            // The name is a nicety; the timestamp already makes the filename unique.
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }

    /**
     * Draw the year's pictures, plus the tile that adds one.
     *
     * <p>The add tile sits at the end rather than the start, so the pictures stay in a stable
     * place as they are added instead of shuffling right each time.
     */
    private void buildShots() {
        if (_shots == null) {
            return;
        }
        _shots.removeAllViews();
        final float density = getResources().getDisplayMetrics().density;
        final int side = Math.round(72 * density);
        final int gap = Math.round(6 * density);

        for (int i = 0; i < _images.size(); i++) {
            final int index = i;
            final android.widget.ImageView iv = new android.widget.ImageView(this);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
            lp.setMarginEnd(gap);
            iv.setLayoutParams(lp);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.tl_notes_card);
            iv.setContentDescription(_images.get(index).getName());
            iv.setOnClickListener(v -> openViewer(index));
            iv.setOnLongClickListener(v -> {
                confirmRemove(index);
                return true;
            });
            net.pixelpen.journal.model.ThumbLoader.load(iv, _images.get(index),
                    Math.max(side, 220));
            _shots.addView(iv);
        }

        final TextView add = new TextView(this);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        add.setLayoutParams(lp);
        add.setBackgroundResource(R.drawable.tl_add_picture);
        add.setGravity(android.view.Gravity.CENTER);
        add.setText("+");
        add.setTextSize(26);
        add.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.tl_header));
        add.setContentDescription(getString(R.string.year_entry_add_image));
        add.setOnClickListener(v -> pickImage());
        _shots.addView(add);
    }

    private void openViewer(final int index) {
        if (index < 0 || index >= _images.size()) {
            return;
        }
        final String[] paths = new String[_images.size()];
        for (int n = 0; n < paths.length; n++) {
            paths[n] = _images.get(n).getAbsolutePath();
        }
        final android.content.Intent intent =
                new android.content.Intent(this, ImageViewerActivity.class);
        intent.putExtra(ImageViewerActivity.EXTRA_PATHS, paths);
        intent.putExtra(ImageViewerActivity.EXTRA_START, index);
        startActivity(intent);
    }

    /**
     * Take a picture off the year.
     *
     * <p>The link goes, the file stays. Pictures are shared out of one folder per year and a
     * second note may well link to the same one; deleting the file here could empty a gap in
     * somewhere this screen cannot see. The file can be deleted from the Tree when it is
     * really finished with.
     */
    private void confirmRemove(final int index) {
        if (index < 0 || index >= _images.size()) {
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(R.string.year_entry_remove_image)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (d, w) -> {
                    _images.remove(index);
                    buildShots();
                    save();
                })
                .show();
    }

    /** Read the file back into months, so editing continues from what is there. */
    private List<List<String>> readExisting() {
        final List<List<String>> byMonth = new ArrayList<>();
        for (int i = 0; i <= UNDATED; i++) {
            byMonth.add(new ArrayList<>());
        }
        if (!_file.isFile()) {
            return byMonth;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(_file), "UTF-8"));
            String line;
            boolean first = true;
            int current = -1;
            while ((line = br.readLine()) != null) {
                final String t = line.trim();
                if (first) {
                    first = false;
                    if (NoteTimestamp.parseAboutDate(t) != null) {
                        continue;
                    }
                }
                if (t.isEmpty() || t.matches("^([-*+]|\\d+\\.)$")) {
                    continue;
                }
                final int heading = Timeline.headingMonthOf(t);
                if (heading >= 0) {
                    current = heading;
                    continue;
                }
                // Pictures come out of the line and into the strip at the top. A link left in
                // a field is markdown for the user to type around and a place for the cursor to
                // land mid-path; and the month it happened to sit under means nothing, since the
                // timeline shows a year's pictures together and records no month against them.
                for (final File img : net.pixelpen.journal.model.ArchiveImages
                        .linkedImages(_file, t)) {
                    if (!_images.contains(img)) {
                        _images.add(img);
                    }
                }
                // Marker first, links second. The other way round, "- ![x](...)" strips to a
                // lone "-" and the marker pattern needs whitespace after the marker to match it,
                // so the line would survive as a bullet reading "-". This is the same trap the
                // seeded month column fell into.
                final String text = t.replaceFirst("^([-*+]|\\d+\\.)\\s+", "");
                final String bare = net.pixelpen.journal.model.ArchiveImages.stripImageLinks(text);
                if (bare.isEmpty()) {
                    // The line was a marker, pictures, or both, and no writing.
                    continue;
                }
                byMonth.get(current >= 0 ? current : UNDATED).add(bare);
            }
        } catch (final Exception e) {
            Toast.makeText(this, R.string.error_could_not_open_file, Toast.LENGTH_SHORT).show();
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (final Exception ignore) {
                // Nothing useful to do if a read-only handle will not close.
            }
        }
        return byMonth;
    }

    /**
     * Write the year back as markdown.
     *
     * <p>Only months with something in them are written. The scaffold was never the content: an
     * empty month is the absence of an entry, and recording twelve of those in every year would
     * put more structure in the file than there is writing.
     */
    private boolean save() {
        final StringBuilder sb = new StringBuilder();
        final Calendar c = Calendar.getInstance();
        c.clear();
        c.set(_year, Calendar.JANUARY, 1);
        sb.append(NoteTimestamp.formatYear(c)).append("\n");

        // Undated first, before any month heading. Written last it would sit underneath December
        // in the file, and reading it back would file it under December -- so an entry whose month
        // is unknown would silently acquire one just by the year being saved twice.
        appendBlock(sb, null, _fields[UNDATED]);
        for (int m = 0; m < 12; m++) {
            appendBlock(sb, Timeline.MONTH_SHORT[m], _fields[m]);
        }

        // Pictures last, one link per line and no heading above them. A heading would read as an
        // entry in the timeline, which keeps any line that is not a list item; a line holding
        // only a link strips to nothing and is skipped, so these stay out of the bullets while
        // remaining ordinary markdown that any editor can see.
        if (!_images.isEmpty()) {
            final File home = net.pixelpen.journal.ApplicationObject.settings()
                    .getNotebookDirectory();
            sb.append("\n");
            for (final File img : _images) {
                sb.append(net.pixelpen.journal.model.ArchiveImages.markdownLink(
                        net.pixelpen.journal.model.ArchiveImages.relativeLink(_file, img, home),
                        img.getName())).append("\n");
            }
        }

        try {
            final File parent = _file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                Toast.makeText(this, R.string.error_could_not_open_file, Toast.LENGTH_SHORT).show();
                return false;
            }
            final FileOutputStream out = new FileOutputStream(_file);
            try {
                out.write(sb.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return true;
        } catch (final Exception e) {
            Toast.makeText(this, R.string.error_could_not_open_file, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /** One month's block, or the headless block of undated entries when {@code heading} is null. */
    private static void appendBlock(final StringBuilder sb, final String heading, final EditText field) {
        if (field == null) {
            return;
        }
        final List<String> lines = split(field.getText().toString());
        if (lines.isEmpty()) {
            return;
        }
        sb.append("\n");
        if (heading != null) {
            sb.append(heading).append("\n");
        }
        for (final String l : lines) {
            sb.append("- ").append(l).append("\n");
        }
    }

    private static String join(final List<String> lines) {
        final StringBuilder sb = new StringBuilder();
        for (final String l : lines) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(l);
        }
        return sb.toString();
    }

    /** One entry per line, blanks dropped. */
    private static List<String> split(final String text) {
        final List<String> out = new ArrayList<>();
        for (final String raw : text.split("\n")) {
            final String t = raw.trim().replaceFirst("^([-*+]|\\d+\\.)\\s+", "");
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
