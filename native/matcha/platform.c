/* Keep the universal APK installable on ABIs without the optional Matcha backend.
 * Android otherwise rejects an APK containing only arm64 libraries on these devices.
 * This library is never loaded. System and HTTP speech remain available. */
int nextvol_platform_marker(void) { return 0; }
