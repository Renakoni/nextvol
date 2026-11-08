package com.autonavi.jni.vcs;

import java.util.List;

/** Required registration surface; reader synthesis uses AudioManagerAdapter. */
public abstract class VcsJniManager implements VCSCallback {
    private native void native_audio_ready();
    private native int native_cancel_reference_playback();
    private native int native_end_reference_data();
    private native String native_get_current_vcs_state();
    private static native String native_get_nui_res_version();
    private static native String native_get_nui_version(String str);
    private static native String native_get_vcs_version();
    private native boolean native_init(long j2, VCSInitParams vCSInitParams);
    private native void native_mock_vocie_cmd(String str);
    private native void native_notify_ajx_render_time(String str);
    private native void native_notify_ajx_wakeup_time(String str);
    private native void native_notify_extra_info(String str);
    private native void native_notify_result(int i, int i8, String str);
    private native void native_notify_result(String str, String str2);
    private static native int native_nui_check_asset(String str);
    private native void native_play_wake_up_sound_end();
    private native void native_push_audio_data(byte[] bArr, int i);
    private native int native_push_reference_data(byte[] bArr, int i, boolean z5, int i8);
    private native boolean native_register_command(List<?> list);
    private native boolean native_release();
    private native boolean native_restart_wakeup_listening();
    private native void native_retry_recognizing();
    private native void native_set_idst_param(String str);
    private native void native_set_ui_thread(long j2);
    private native void native_start_recognizing_manually();
    private native boolean native_start_wakeup_listening();
    private native boolean native_start_wakeup_manually(String str);
    private native boolean native_stop_listening(String str);
    private native void native_text2action(String str);
    private native boolean native_update_audio_data(byte[] bArr, int i, boolean z5);
}
