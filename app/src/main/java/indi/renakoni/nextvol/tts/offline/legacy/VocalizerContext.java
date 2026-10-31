package indi.renakoni.nextvol.tts.offline.legacy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.VersionedPackage;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import java.util.List;

/** The SDK's local identity view; application resources and system identity remain with the host. */
@SuppressWarnings("deprecation")
public final class VocalizerContext extends ContextWrapper {
    private static final String SDK_PACKAGE = "es.codefactory.vocalizertts";
    private final PackageManager packages;

    public VocalizerContext(Context base, byte[][] certificates) {
        super(base);
        packages = new SdkPackages(base.getPackageManager(), base.getPackageName(), certificates);
    }

    @Override public String getPackageName() { return SDK_PACKAGE; }
    @Override public PackageManager getPackageManager() { return packages; }

    // Newer abstract framework methods are forwarded only when called on an API that provides them.
    @SuppressLint("NewApi")
    private static final class SdkPackages extends PackageManager {
        private final PackageManager delegate;
        private final String hostPackage;
        private final Signature[] signatures;

        private SdkPackages(PackageManager delegate, String hostPackage, byte[][] certificates) {
            this.delegate = delegate;
            this.hostPackage = hostPackage;
            if (certificates.length == 0) throw new IllegalArgumentException("Vocalizer SDK certificate is missing");
            signatures = new Signature[certificates.length];
            for (int i = 0; i < certificates.length; i++) signatures[i] = new Signature(certificates[i]);
        }

        private String hostPackage(String name) { return SDK_PACKAGE.equals(name) ? hostPackage : name; }

        private PackageInfo sdkPackageInfo() {
            PackageInfo info = new PackageInfo();
            info.packageName = SDK_PACKAGE;
            info.signatures = signatures.clone();
            return info;
        }

        @Override public void addPackageToPreferred(String p0) { delegate.addPackageToPreferred(p0); }
        @Override public boolean addPermission(PermissionInfo p0) { return delegate.addPermission(p0); }
        @Override public boolean addPermissionAsync(PermissionInfo p0) { return delegate.addPermissionAsync(p0); }
        @Override public void addPreferredActivity(IntentFilter p0, int p1, ComponentName[] p2, ComponentName p3) { delegate.addPreferredActivity(p0, p1, p2, p3); }
        @Override public boolean canRequestPackageInstalls() { return delegate.canRequestPackageInstalls(); }
        @Override public String[] canonicalToCurrentPackageNames(String[] p0) { return delegate.canonicalToCurrentPackageNames(p0); }
        @Override public int checkPermission(String p0, String p1) { return delegate.checkPermission(p0, p1); }
        @Override public int checkSignatures(int p0, int p1) { return delegate.checkSignatures(p0, p1); }
        @Override public int checkSignatures(String p0, String p1) { return delegate.checkSignatures(p0, p1); }
        @Override public void clearInstantAppCookie() { delegate.clearInstantAppCookie(); }
        @Override public void clearPackagePreferredActivities(String p0) { delegate.clearPackagePreferredActivities(p0); }
        @Override public String[] currentToCanonicalPackageNames(String[] p0) { return delegate.currentToCanonicalPackageNames(p0); }
        @Override public void extendVerificationTimeout(int p0, int p1, long p2) { delegate.extendVerificationTimeout(p0, p1, p2); }
        @Override public Drawable getActivityBanner(ComponentName p0) throws NameNotFoundException { return delegate.getActivityBanner(p0); }
        @Override public Drawable getActivityBanner(Intent p0) throws NameNotFoundException { return delegate.getActivityBanner(p0); }
        @Override public Drawable getActivityIcon(ComponentName p0) throws NameNotFoundException { return delegate.getActivityIcon(p0); }
        @Override public Drawable getActivityIcon(Intent p0) throws NameNotFoundException { return delegate.getActivityIcon(p0); }
        @Override public ActivityInfo getActivityInfo(ComponentName p0, int p1) throws NameNotFoundException { return delegate.getActivityInfo(p0, p1); }
        @Override public Drawable getActivityLogo(ComponentName p0) throws NameNotFoundException { return delegate.getActivityLogo(p0); }
        @Override public Drawable getActivityLogo(Intent p0) throws NameNotFoundException { return delegate.getActivityLogo(p0); }
        @Override public List<PermissionGroupInfo> getAllPermissionGroups(int p0) { return delegate.getAllPermissionGroups(p0); }
        @Override public Drawable getApplicationBanner(ApplicationInfo p0) { return delegate.getApplicationBanner(p0); }
        @Override public Drawable getApplicationBanner(String p0) throws NameNotFoundException { return delegate.getApplicationBanner(p0); }
        @Override public int getApplicationEnabledSetting(String p0) { return delegate.getApplicationEnabledSetting(p0); }
        @Override public Drawable getApplicationIcon(ApplicationInfo p0) { return delegate.getApplicationIcon(p0); }
        @Override public Drawable getApplicationIcon(String p0) throws NameNotFoundException { return delegate.getApplicationIcon(hostPackage(p0)); }
        @Override public ApplicationInfo getApplicationInfo(String p0, int p1) throws NameNotFoundException { return delegate.getApplicationInfo(hostPackage(p0), p1); }
        @Override public CharSequence getApplicationLabel(ApplicationInfo p0) { return delegate.getApplicationLabel(p0); }
        @Override public Drawable getApplicationLogo(ApplicationInfo p0) { return delegate.getApplicationLogo(p0); }
        @Override public Drawable getApplicationLogo(String p0) throws NameNotFoundException { return delegate.getApplicationLogo(p0); }
        @Override public ChangedPackages getChangedPackages(int p0) { return delegate.getChangedPackages(p0); }
        @Override public int getComponentEnabledSetting(ComponentName p0) { return delegate.getComponentEnabledSetting(p0); }
        @Override public Drawable getDefaultActivityIcon() { return delegate.getDefaultActivityIcon(); }
        @Override public Drawable getDrawable(String p0, int p1, ApplicationInfo p2) { return delegate.getDrawable(p0, p1, p2); }
        @Override public List<ApplicationInfo> getInstalledApplications(int p0) { return delegate.getInstalledApplications(p0); }
        @Override public List<android.content.pm.PackageInfo> getInstalledPackages(int p0) { return delegate.getInstalledPackages(p0); }
        @Override public String getInstallerPackageName(String p0) { return SDK_PACKAGE.equals(p0) ? SDK_PACKAGE : delegate.getInstallerPackageName(p0); }
        @Override public byte[] getInstantAppCookie() { return delegate.getInstantAppCookie(); }
        @Override public int getInstantAppCookieMaxBytes() { return delegate.getInstantAppCookieMaxBytes(); }
        @Override public InstrumentationInfo getInstrumentationInfo(ComponentName p0, int p1) throws NameNotFoundException { return delegate.getInstrumentationInfo(p0, p1); }
        @Override public Intent getLaunchIntentForPackage(String p0) { return delegate.getLaunchIntentForPackage(p0); }
        @Override public Intent getLeanbackLaunchIntentForPackage(String p0) { return delegate.getLeanbackLaunchIntentForPackage(p0); }
        @Override public String getNameForUid(int p0) { return delegate.getNameForUid(p0); }
        @Override public int[] getPackageGids(String p0) throws NameNotFoundException { return delegate.getPackageGids(p0); }
        @Override public int[] getPackageGids(String p0, int p1) throws NameNotFoundException { return delegate.getPackageGids(p0, p1); }
        @Override public android.content.pm.PackageInfo getPackageInfo(VersionedPackage p0, int p1) throws NameNotFoundException { return SDK_PACKAGE.equals(p0.getPackageName()) ? sdkPackageInfo() : delegate.getPackageInfo(p0, p1); }
        @Override public android.content.pm.PackageInfo getPackageInfo(String p0, int p1) throws NameNotFoundException { return SDK_PACKAGE.equals(p0) ? sdkPackageInfo() : delegate.getPackageInfo(p0, p1); }
        @Override public PackageInstaller getPackageInstaller() { return delegate.getPackageInstaller(); }
        @Override public int getPackageUid(String p0, int p1) throws NameNotFoundException { return delegate.getPackageUid(hostPackage(p0), p1); }
        @Override public String[] getPackagesForUid(int p0) { return delegate.getPackagesForUid(p0); }
        @Override public List<android.content.pm.PackageInfo> getPackagesHoldingPermissions(String[] p0, int p1) { return delegate.getPackagesHoldingPermissions(p0, p1); }
        @Override public PermissionGroupInfo getPermissionGroupInfo(String p0, int p1) throws NameNotFoundException { return delegate.getPermissionGroupInfo(p0, p1); }
        @Override public PermissionInfo getPermissionInfo(String p0, int p1) throws NameNotFoundException { return delegate.getPermissionInfo(p0, p1); }
        @Override public int getPreferredActivities(List<IntentFilter> p0, List<ComponentName> p1, String p2) { return delegate.getPreferredActivities(p0, p1, p2); }
        @Override public List<android.content.pm.PackageInfo> getPreferredPackages(int p0) { return delegate.getPreferredPackages(p0); }
        @Override public ProviderInfo getProviderInfo(ComponentName p0, int p1) throws NameNotFoundException { return delegate.getProviderInfo(p0, p1); }
        @Override public ActivityInfo getReceiverInfo(ComponentName p0, int p1) throws NameNotFoundException { return delegate.getReceiverInfo(p0, p1); }
        @Override public Resources getResourcesForActivity(ComponentName p0) throws NameNotFoundException { return delegate.getResourcesForActivity(p0); }
        @Override public Resources getResourcesForApplication(ApplicationInfo p0) throws NameNotFoundException { return delegate.getResourcesForApplication(p0); }
        @Override public Resources getResourcesForApplication(String p0) throws NameNotFoundException { return delegate.getResourcesForApplication(hostPackage(p0)); }
        @Override public ServiceInfo getServiceInfo(ComponentName p0, int p1) throws NameNotFoundException { return delegate.getServiceInfo(p0, p1); }
        @Override public List<SharedLibraryInfo> getSharedLibraries(int p0) { return delegate.getSharedLibraries(p0); }
        @Override public FeatureInfo[] getSystemAvailableFeatures() { return delegate.getSystemAvailableFeatures(); }
        @Override public String[] getSystemSharedLibraryNames() { return delegate.getSystemSharedLibraryNames(); }
        @Override public CharSequence getText(String p0, int p1, ApplicationInfo p2) { return delegate.getText(p0, p1, p2); }
        @Override public Drawable getUserBadgedDrawableForDensity(Drawable p0, UserHandle p1, Rect p2, int p3) { return delegate.getUserBadgedDrawableForDensity(p0, p1, p2, p3); }
        @Override public Drawable getUserBadgedIcon(Drawable p0, UserHandle p1) { return delegate.getUserBadgedIcon(p0, p1); }
        @Override public CharSequence getUserBadgedLabel(CharSequence p0, UserHandle p1) { return delegate.getUserBadgedLabel(p0, p1); }
        @Override public XmlResourceParser getXml(String p0, int p1, ApplicationInfo p2) { return delegate.getXml(p0, p1, p2); }
        @Override public boolean hasSystemFeature(String p0) { return delegate.hasSystemFeature(p0); }
        @Override public boolean hasSystemFeature(String p0, int p1) { return delegate.hasSystemFeature(p0, p1); }
        @Override public boolean isInstantApp() { return delegate.isInstantApp(); }
        @Override public boolean isInstantApp(String p0) { return delegate.isInstantApp(p0); }
        @Override public boolean isPermissionRevokedByPolicy(String p0, String p1) { return delegate.isPermissionRevokedByPolicy(p0, p1); }
        @Override public boolean isSafeMode() { return delegate.isSafeMode(); }
        @Override public List<ResolveInfo> queryBroadcastReceivers(Intent p0, int p1) { return delegate.queryBroadcastReceivers(p0, p1); }
        @Override public List<ProviderInfo> queryContentProviders(String p0, int p1, int p2) { return delegate.queryContentProviders(p0, p1, p2); }
        @Override public List<InstrumentationInfo> queryInstrumentation(String p0, int p1) { return delegate.queryInstrumentation(p0, p1); }
        @Override public List<ResolveInfo> queryIntentActivities(Intent p0, int p1) { return delegate.queryIntentActivities(p0, p1); }
        @Override public List<ResolveInfo> queryIntentActivityOptions(ComponentName p0, Intent[] p1, Intent p2, int p3) { return delegate.queryIntentActivityOptions(p0, p1, p2, p3); }
        @Override public List<ResolveInfo> queryIntentContentProviders(Intent p0, int p1) { return delegate.queryIntentContentProviders(p0, p1); }
        @Override public List<ResolveInfo> queryIntentServices(Intent p0, int p1) { return delegate.queryIntentServices(p0, p1); }
        @Override public List<PermissionInfo> queryPermissionsByGroup(String p0, int p1) throws NameNotFoundException { return delegate.queryPermissionsByGroup(p0, p1); }
        @Override public void removePackageFromPreferred(String p0) { delegate.removePackageFromPreferred(p0); }
        @Override public void removePermission(String p0) { delegate.removePermission(p0); }
        @Override public ResolveInfo resolveActivity(Intent p0, int p1) { return delegate.resolveActivity(p0, p1); }
        @Override public ProviderInfo resolveContentProvider(String p0, int p1) { return delegate.resolveContentProvider(p0, p1); }
        @Override public ResolveInfo resolveService(Intent p0, int p1) { return delegate.resolveService(p0, p1); }
        @Override public void setApplicationCategoryHint(String p0, int p1) { delegate.setApplicationCategoryHint(hostPackage(p0), p1); }
        @Override public void setApplicationEnabledSetting(String p0, int p1, int p2) { delegate.setApplicationEnabledSetting(p0, p1, p2); }
        @Override public void setComponentEnabledSetting(ComponentName p0, int p1, int p2) { delegate.setComponentEnabledSetting(p0, p1, p2); }
        @Override public void setInstallerPackageName(String p0, String p1) { delegate.setInstallerPackageName(p0, p1); }
        @Override public void updateInstantAppCookie(byte[] p0) { delegate.updateInstantAppCookie(p0); }
        @Override public void verifyPendingInstall(int p0, int p1) { delegate.verifyPendingInstall(p0, p1); }
    }
}
