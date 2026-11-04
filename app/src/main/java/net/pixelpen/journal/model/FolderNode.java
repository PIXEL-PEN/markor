package net.pixelpen.journal.model;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FolderNode {
    public String name;
    public File file;
    public List<FolderNode> subfolders = new ArrayList<>();
    public List<FileNode> files = new ArrayList<>();
    public boolean expanded = false;  // Track open/collapse state
    public int depth = 0;             // Nesting level (for padding/styling)

    public FolderNode(String name, File file) {
        this.name = name;
        this.file = file;
    }
}
