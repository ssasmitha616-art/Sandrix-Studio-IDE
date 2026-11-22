package com.example.sandrix.ide;

import java.io.File;

public final class FileNode {
    public final File file;
    public final String name;
    public final int depth;
    public final boolean isDirectory;
    public final boolean isExpanded;

    public FileNode(File file, String name, int depth, boolean isDirectory, boolean isExpanded) {
        this.file = file;
        this.name = name;
        this.depth = depth;
        this.isDirectory = isDirectory;
        this.isExpanded = isExpanded;
    }
}
