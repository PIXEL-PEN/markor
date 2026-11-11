package net.pixelpen.journal.activity;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.pixelpen.journal.ApplicationObject;
import net.pixelpen.journal.R;
import net.pixelpen.journal.adapter.FolderTreeAdapter;
import net.pixelpen.journal.model.ArchiveSearch;
import net.pixelpen.journal.model.CreationIndex;
import net.pixelpen.journal.model.TreeStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Search the archive, from anywhere.
 *
 * <p>The field used to sit in the main toolbar and draw its results in the Tree. That worked on
 * the Tree and nowhere else: from the Calendar or Year screens you could type a query and watch
 * nothing happen, because the only surface able to show matches was a tab away. Jumping to that
 * tab was worse -- asking to search is not asking to be moved.
 *
 * <p>So the results have a screen of their own, which any screen can open. The Tree keeps its own
 * inline search, where matches genuinely do replace what is already there.
 */
public class SearchActivity extends AutumnBaseActivity implements FolderTreeAdapter.Listener {

    /** Wait for typing to stop before walking the archive. See the Tree's search for why. */
    private static final long DEBOUNCE_MS = 250L;

    private RecyclerView _list;
    private TextView _message;
    private FolderTreeAdapter _adapter;
    private CreationIndex _index;

    private final Handler _delay = new Handler(Looper.getMainLooper());
    private Runnable _pending;
    private String _query;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        setSupportActionBar(findViewById(R.id.search_toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.search);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        _index = new CreationIndex(this);
        _list = findViewById(R.id.search_results);
        _message = findViewById(R.id.search_message);
        _adapter = new FolderTreeAdapter(this);
        _list.setLayoutManager(new LinearLayoutManager(this));
        _list.setAdapter(_adapter);
        _list.setItemAnimator(null);

        _message.setText(R.string.search_prompt);

        // The field is part of the screen now, not an action view collapsed into the toolbar.
        // It had been expanded on arrival anyway, so it was already behaving like a fixed field
        // while living somewhere that made it look temporary.
        final SearchView search = findViewById(R.id.search_input);
        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(final String query) {
                // Already matched as it was typed; the key just dismisses the keyboard.
                search.clearFocus();
                return true;
            }

            @Override
            public boolean onQueryTextChange(final String text) {
                setQuery(text);
                return true;
            }
        });
        // This screen exists only to be typed into.
        search.requestFocus();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setQuery(final String text) {
        final String trimmed = (text == null) ? null : text.trim();
        final String next = (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
        if (next == null ? _query == null : next.equals(_query)) {
            return;
        }
        _query = next;

        if (_pending != null) {
            _delay.removeCallbacks(_pending);
            _pending = null;
        }
        if (_query == null) {
            _adapter.setRows(new ArrayList<>());
            _message.setText(R.string.search_prompt);
            _message.setVisibility(View.VISIBLE);
            return;
        }
        _pending = this::run;
        _delay.postDelayed(_pending, DEBOUNCE_MS);
    }

    private void run() {
        final File root = ApplicationObject.settings().getNotebookDirectory();
        final String query = _query;
        if (query == null) {
            return;
        }
        new Thread(() -> {
            final List<ArchiveSearch.Hit> hits = ArchiveSearch.run(root, query, _index);
            final List<FolderTreeAdapter.Row> rows = new ArrayList<>(hits.size());
            for (final ArchiveSearch.Hit h : hits) {
                final int cut = h.where.lastIndexOf('/');
                final String category = (cut >= 0) ? h.where.substring(cut + 1) : h.where;
                // The matched text, not "line 6". The line number is still passed to the
                // editor so the result opens in the right place; it just stopped being the
                // only thing the row said about the match.
                final String where = h.inBody && !h.snippet.isEmpty()
                        ? category + "  ·  " + h.snippet
                        : category;
                rows.add(new FolderTreeAdapter.Row(
                        TreeStore.Entry.forNote(h.file, h.display, h.createdMillis),
                        0, false, where, h.line));
            }
            runOnUiThread(() -> {
                // The query may have moved on while the walk ran; those rows answer a question
                // nobody is asking any more.
                if (isFinishing() || !query.equals(_query)) {
                    return;
                }
                _adapter.setRows(rows);
                _list.scrollToPosition(0);
                _message.setText(getString(R.string.tree_search_no_results, query));
                _message.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    @Override
    protected void onDestroy() {
        if (_pending != null) {
            _delay.removeCallbacks(_pending);
            _pending = null;
        }
        super.onDestroy();
    }

    // Results are notes and nothing else: there are no folders to open here, and the actions that
    // act on a note in place belong where it lives rather than in a list of matches.

    @Override
    public void onFolderToggled(final FolderTreeAdapter.Row row) {
    }

    @Override
    public boolean onFileLongPressed(final File file) {
        return false;
    }

    @Override
    public boolean onFolderLongPressed(final File dir) {
        return false;
    }

    @Override
    public void onSectionToggled() {
    }
}
