package com.samsung.SMT.ref;

import androidx.annotation.Keep;
import java.util.Locale;

/** File descriptor metadata consumed by Samsung's native loader. */
@Keep
public final class VoiceDataInfo {
    private int fd;
    private String filename;
    private long length;
    private long offset;
    private int type;
    private int version;

    public void setFDInfo(int fd, long offset, long length) {
        this.fd = fd;
        this.offset = offset;
        this.length = length;
    }

    public void setFileInfo(String filename) {
        this.filename = filename;
        String name = filename.toLowerCase(Locale.ROOT);
        String[] suffixes = {"", "dt", "qs", "am", "cfg", "lng", "cart", "pb", "ivc", "skips", "bin"};
        this.type = 0;
        for (int index = 1; index < suffixes.length; index++) {
            if (name.endsWith(suffixes[index])) { this.type = index; break; }
        }
        if (name.startsWith("cache")) this.type = 11;
    }

    public int getFD() { return fd; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
