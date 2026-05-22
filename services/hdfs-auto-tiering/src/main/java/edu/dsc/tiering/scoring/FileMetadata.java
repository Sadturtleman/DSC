package edu.dsc.tiering.scoring;

public class FileMetadata {
    private final String path;
    private final long size;
    private final long atime;
    private final long mtime;
    private final int storagePolicy;

    public FileMetadata(String path, long size, long atime, long mtime, int storagePolicy) {
        this.path = path;
        this.size = size;
        this.atime = atime;
        this.mtime = mtime;
        this.storagePolicy = storagePolicy;
    }

    public String path() { return path; }
    public long size() { return size; }
    public long atime() { return atime; }
    public long mtime() { return mtime; }
    public int storagePolicy() { return storagePolicy; }
}
