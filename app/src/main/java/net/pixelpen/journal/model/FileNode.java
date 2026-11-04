package net.pixelpen.journal.model;

import java.io.File;
import java.util.Date;

// New imports for creation time (safe fallback if unavailable)
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

public class FileNode {
    public String name;
    public File file;
    public Date lastModified;
    public int depth;

    // NEW: canonical creation timestamp (ms) + convenience Date
    public long createdMillis;
    public Date createdDate;

    public FileNode(String name, File file, int depth) {
        this.name = name;
        this.file = file;
        this.depth = depth;
        this.lastModified = new Date(file.lastModified());

        // Try true filesystem creation time; fallback to lastModified
        long created = 0L;
        try {
            Path p = file.toPath();
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            if (attrs != null && attrs.creationTime() != null) {
                created = attrs.creationTime().toMillis();
            }
        } catch (Throwable ignore) {
            // Some devices/FS don't expose creationTime; fall through
        }
        if (created <= 0L) {
            created = file.lastModified();
        }
        this.createdMillis = created;
        this.createdDate = new Date(createdMillis);
    }
}


