package indi.renakoni.nextvol.tts.offline.legacy;

import android.app.Application;
import android.content.Context;
import com.bytedance.ttnet.ITTNetDepend;
import com.bytedance.ttnet.TTNetInit;
import com.bytedance.ttnet.cronet.AbsCronetDependAdapter;
import org.chromium.CronetAppProviderManager;
import org.chromium.CronetDependManager;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** SpeechEngine PrepareEnvironment / MultiTTS r2.B0 and r2.y0 defaults. */
final class BytedanceEnvironment implements ITTNetDepend {
    private static boolean prepared;
    private final Context context;

    private BytedanceEnvironment(Context context) { this.context = context; }

    static synchronized void prepare(Context context) throws Exception {
        if (prepared) return;
        Context app = context.getApplicationContext();
        AbsCronetDependAdapter adapter = new AbsCronetDependAdapter() {
            @Override public String getAppId() { return "1234"; }
            @Override public String getAppName() { return "SpeechDemo"; }
            @Override public String getDeviceId() { return "09876"; }
            @Override public String getStoreIdc() { return "TestIDC"; }
            @Override public boolean loggerDebug() { return false; }
            @Override public void sendAppMonitorEvent(String event, String value) {}
            @Override public String getGetDomainDefaultJSON() {
                return "{\"data\":{\"chromium_open\":1,\"ttnet_http_dns_enabled\":0,\"ttnet_quic_enabled\":0,"
                    + "\"ttnet_local_dns_time_out\":2,\"ttnet_nqe_watching_groups\":[\"api.hypstar.com\",\"*\",\"www.baidu.com\"],"
                    + "\"tc\":{\"drop\":[{\"pattern\":\"dm.\",\"value\":100}]},"
                    + "\"ttnet_min_dns_ttl\":{\"dns.google.com\":600,\"api.huoshan.com\":6000},"
                    + "\"ttnet_quic_hint\":{\"ic.snssdk.com\":443,\"www.google.com\":443},"
                    + "\"ttnet_socket_pool_param\":{\"max_sockets_per_group\":20},"
                    + "\"ttnet_quic_internal_param\":{\"congestion_control_type\":4,\"icwnd_size\":50},"
                    + "\"ttnet_persistent_host_cache_param\":{\"enable_persistent_host_cache\":1,"
                    + "\"first_saving_interval\":10,\"repeat_saving_interval\":30}},\"message\":\"success\"}";
            }
        };
        CronetDependManager.inst().setAdapter(adapter);
        CronetAppProviderManager.inst().setAdapter(adapter);
        TTNetInit.setTTNetDepend(new BytedanceEnvironment(app));
        TTNetInit.tryInitTTNet(app, (Application) app, null, null, null, true, false);
        TTNetInit.forceInitCronetKernel();
        prepared = true;
    }

    @Override public Context getContext() { return context; }
    @Override public int getAppId() { return 2667; }
    @Override public String getApiIHostPrefix() { return "ib"; }
    @Override public String getCdnHostSuffix() { return "pstat.com"; }
    @Override public String getHostSuffix() { return "snssdk.com"; }
    @Override public String[] getConfigServers() { return new String[0]; }
    @Override public boolean isCronetPluginInstalled() { return true; }
    @Override public boolean isPrivateApiAccessEnabled() { return true; }
    @Override public Map<String, String> getTTNetServiceDomainMap() {
        Map<String, String> domains = new HashMap<>();
        domains.put("httpdns", "xxx.xx.xxx");
        domains.put(TTNetInit.DOMAIN_NETLOG_KEY, "xxx.xx.xxx");
        domains.put(TTNetInit.DOMAIN_BOE_KEY, "xxx.xx.xxx");
        return domains;
    }
    @Override public int checkHttpRequestException(Throwable error, String[] hosts) { return 0; }
    @Override public String executeGet(int limit, String url) { return null; }
    @Override public ArrayList<String> getCookieFlushPathList() { return null; }
    @Override public Map<String, String> getHostReverseMap() { return null; }
    @Override public int getProviderInt(Context context, String key, int fallback) { return 0; }
    @Override public String getProviderString(Context context, String key, String fallback) { return null; }
    @Override public String getShareCookieMainDomain() { return null; }
    @Override public void onColdStartFinish() {}
    @Override public void onShareCookieConfigUpdated(String config) {}
    @Override public void monitorLogSend(String event, JSONObject data) {}
    @Override public void onAppConfigUpdated(Context context, JSONObject config) {}
    @Override public void onNetConfigUpdate(JSONObject config, boolean changed) {}
    @Override public void saveMapToProvider(Context context, Map<String, ?> values) {}
    @Override public void mobOnEvent(Context context, String event, String label, JSONObject data) {}
}
