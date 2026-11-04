package net.pixelpen.journal.adapter;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import net.pixelpen.journal.BuildConfig;
import net.pixelpen.journal.R;
import net.pixelpen.journal.model.FileNode;
import net.pixelpen.journal.model.FolderNode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FolderTreeAdapter extends RecyclerView.Adapter<FolderTreeAdapter.NodeViewHolder> {
    private final List<Object> visibleItems = new ArrayList<>();
    private final FolderNode rootNode;

    public FolderTreeAdapter(FolderNode rootNode) {
        this.rootNode = rootNode;
        rebuildVisibleItems();
    }

    @Override
    public NodeViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.folder_tree_item, parent, false);
        return new NodeViewHolder(view);
    }

    @Override
    public void onBindViewHolder(NodeViewHolder holder, int position) {
        Object item = visibleItems.get(position);

        if (item instanceof FolderNode) {
            FolderNode folder = (FolderNode) item;
            holder.icon.setText(folder.expanded ? "▾" : "▸");

            // Remove number prefix from month folders (e.g., 01_January → January)
            String displayName = folder.name;
            if (folder.depth == 2 && folder.name.contains("_")) {
                displayName = folder.name.substring(folder.name.indexOf("_") + 1);
            }
            holder.name.setText(displayName);

            int depth = folder.depth;
            int padding = 20 * depth;
            holder.itemView.setPadding(
                    padding,
                    holder.itemView.getPaddingTop(),
                    20,
                    holder.itemView.getPaddingBottom()
            );

            if (depth == 2) {
                holder.name.setTextSize(19);
                holder.name.setTypeface(Typeface.DEFAULT);
                holder.name.setTextColor(android.graphics.Color.DKGRAY);
            } else if (depth == 3) {
                holder.name.setTextSize(17);
                holder.name.setTypeface(Typeface.DEFAULT_BOLD);
                holder.name.setTextColor(android.graphics.Color.BLACK);
            } else {
                holder.name.setTextSize(16);
                holder.name.setTypeface(Typeface.DEFAULT);
                holder.name.setTextColor(android.graphics.Color.BLACK);
            }

            holder.itemView.setOnClickListener(v -> {
                folder.expanded = !folder.expanded;
                rebuildVisibleItems();
                notifyDataSetChanged();
            });

        } else if (item instanceof FileNode) {
            FileNode file = (FileNode) item;
            holder.icon.setText("\uD83D\uDCC4");
            holder.name.setText(file.name);
            holder.name.setTextSize(16);
            holder.name.setTypeface(Typeface.DEFAULT);
            holder.name.setTextColor(android.graphics.Color.BLACK);

            int padding = 20 * file.depth;
            holder.itemView.setPadding(
                    padding,
                    holder.itemView.getPaddingTop(),
                    20,
                    holder.itemView.getPaddingBottom()
            );

            holder.itemView.setOnClickListener(v -> {
                // 1) Use the actual scanned file first
                java.io.File noteFile = file.file;

                // 2) If missing (e.g., ultra-long title truncated by FS), try best prefix match
                if (noteFile == null || !noteFile.isFile()) {
                    java.io.File parent = (noteFile != null) ? noteFile.getParentFile() : null;
                    if (parent == null && file.file != null) {
                        parent = file.file.getParentFile();
                    }
                    if (parent != null && parent.isDirectory()) {
                        java.io.File fallback = findBestPrefixMatch(parent, file.name);
                        if (fallback != null && fallback.isFile()) {
                            noteFile = fallback;
                        }
                    }
                }

                if (noteFile != null && noteFile.isFile()) {
                    String fname = noteFile.getName().toLowerCase(java.util.Locale.ROOT);
                    boolean isHtml = fname.endsWith(".html") || fname.endsWith(".htm");

                    if (isHtml) {
                        // Open HTML in our FileOpenActivity (explicit component)
                        // -- HTML path (no direct FileOpenActivity reference) --
                        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                                v.getContext(),
                                BuildConfig.APPLICATION_ID + ".provider",   // IMPORTANT: .provider (not .fileprovider)
                                noteFile
                        );
                        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                        i.setDataAndType(uri, "text/html");
                        i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
// Force resolution to THIS app (prevents the original Markor/browser)
                        i.setPackage(v.getContext().getPackageName());
                        v.getContext().startActivity(i);

                    } else {
                        // Open text docs in our DocumentActivity (explicit component)
                        android.content.Intent i = new android.content.Intent(
                                v.getContext(), net.pixelpen.journal.activity.DocumentActivity.class);
                        i.putExtra(net.pixelpen.journal.model.Document.EXTRA_FILE, noteFile);
                        i.putExtra(net.pixelpen.journal.model.Document.EXTRA_DO_PREVIEW, true);
                        v.getContext().startActivity(i);
                    }
                } else {
                    android.widget.Toast.makeText(v.getContext(), "File not found", android.widget.Toast.LENGTH_SHORT).show();
                    android.util.Log.e("FolderTree", "⚠️ Missing file: " + file.name);
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return visibleItems.size();
    }

    static class NodeViewHolder extends RecyclerView.ViewHolder {
        TextView icon;
        TextView name;

        NodeViewHolder(View view) {
            super(view);
            icon = view.findViewById(R.id.item_icon);
            name = view.findViewById(R.id.item_name);
        }
    }

    private void rebuildVisibleItems() {
        visibleItems.clear();
        buildVisibleItemsRecursive(rootNode);
    }

    private void buildVisibleItemsRecursive(FolderNode node) {
        visibleItems.add(node);
        if (node.expanded) {
            for (FolderNode sub : node.subfolders) {
                buildVisibleItemsRecursive(sub);
            }
            visibleItems.addAll(node.files);
        }
    }

    // Helper: find best filename match when FS truncated a very long title
    private static java.io.File findBestPrefixMatch(java.io.File parent, String displayName) {
        if (displayName == null || displayName.isEmpty()) return null;

        // stem of the displayed name (without extension)
        String base = displayName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);

        // limit the matching prefix (avoid extremely long probes)
        int max = Math.min(200, base.length());
        String probe = base.substring(0, max);

        java.io.File[] kids = parent.listFiles();
        if (kids == null || kids.length == 0) return null;

        java.io.File best = null;
        int bestLen = -1;

        for (java.io.File k : kids) {
            if (!k.isFile()) continue;
            String name = k.getName();
            String stem = name;
            int d = stem.lastIndexOf('.');
            if (d > 0) stem = stem.substring(0, d);

            if (stem.startsWith(probe)) {
                if (name.length() > bestLen) {
                    best = k;
                    bestLen = name.length();
                }
            }
        }
        return best;
    }
}
