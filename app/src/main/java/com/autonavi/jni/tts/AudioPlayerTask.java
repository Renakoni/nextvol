package com.autonavi.jni.tts;

/** Native-populated fields retain their original names. */
public final class AudioPlayerTask {
    public static final short TASKTYPE_DYAUDIOVAULE = 5;
    public static final short TASKTYPE_FILEVALUE = 1;
    public static final short TASKTYPE_RESIDVAULE = 3;
    public static final short TASKTYPE_RESVALUE = 2;
    public static final short TASKTYPE_TTSVALUE = 0;
    public static final short TASKTYPE_URLVAULE = 4;
    public String data;
    public short owner;
    public int tag;
    public long taskId;
    public short taskType;
}
