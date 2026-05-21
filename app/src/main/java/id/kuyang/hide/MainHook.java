package id.kuyang.hide;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * KuyHide Helper - Xposed Module by KuyangID
 * Telegram: t.me/KuyangID
 * 
 * All-in-one module to:
 * 1. Hide USB Debugging / ADB status from apps
 * 2. Hide Developer Options status from apps
 * 3. Hide Accessibility services from apps  
 * 4. Hide debugger connection status
 * 5. Keep ADB actually enabled in the background
 *
 * Works by hooking Android framework methods that apps use
 * to detect these states.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "KuyHide";

    // =============================================
    // Settings keys we want to hide
    // =============================================
    
    // Global settings to hide (return 0 / "0")
    private static final String[] GLOBAL_HIDE_INT_KEYS = {
        "adb_enabled",                      // USB Debugging
        "development_settings_enabled",     // Developer Options
        "adb_wifi_enabled",                 // Wireless debugging
    };

    // Secure settings to hide (return 0)
    private static final String[] SECURE_HIDE_INT_KEYS = {
        "accessibility_enabled",            // Accessibility ON/OFF
        "adb_enabled",
        "development_settings_enabled",
        "adb_wifi_enabled",
    };


    // Secure settings to return empty string
    private static final String[] SECURE_HIDE_STRING_KEYS = {
        "enabled_accessibility_services",   // List of active a11y services
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // Skip hooking our own module
        if (lpparam.packageName.equals("id.kuyang.hide")) return;

        if (lpparam.packageName.equals("android")) {
            log("Loaded in system server (android). Setting up auto ADB...");
            autoEnableAdb(lpparam);
            autoAllowAdb(lpparam);
            forceAdbEnabledForSystem(lpparam);
            return;
        }

        // Skip settings app & settings provider to avoid interfering with system UI and settings database
        if (lpparam.packageName.equals("com.android.settings") || 
            lpparam.packageName.equals("com.android.providers.settings")) {
            return;
        }

        log("Loaded in app: " + lpparam.packageName);

        // Apply all hooks for third-party apps
        hookSettingsGlobal(lpparam);
        hookSettingsSecure(lpparam);
        hookDebugClass(lpparam);
        hookSystemProperties(lpparam);
    }

    private void autoEnableAdb(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> ams = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", lpparam.classLoader);
            
            XposedBridge.hookAllMethods(ams, "systemReady", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    log("systemReady called, initializing auto ADB background monitor...");
                    try {
                        final android.content.Context context = (android.content.Context) 
                            XposedHelpers.getObjectField(param.thisObject, "mContext");
                        
                        if (context != null) {
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        // Sleep 5 seconds to let settings provider settle after boot
                                        Thread.sleep(5000);
                                        
                                        Class<?> sysProps = XposedHelpers.findClass(
                                            "android.os.SystemProperties", lpparam.classLoader);
                                        
                                        while (true) {
                                            try {
                                                android.content.ContentResolver resolver = context.getContentResolver();
                                                
                                                // 1. Enable USB Debugging in settings
                                                int adbEnabled = android.provider.Settings.Global.getInt(
                                                    resolver, android.provider.Settings.Global.ADB_ENABLED, 0);
                                                if (adbEnabled != 1) {
                                                    log("ADB database is disabled. Auto-enabling ADB...");
                                                    android.provider.Settings.Global.putInt(
                                                        resolver, android.provider.Settings.Global.ADB_ENABLED, 1);
                                                }
                                                
                                                // 2. Enable Developer Options in settings
                                                int devOptsEnabled = android.provider.Settings.Global.getInt(
                                                    resolver, "development_settings_enabled", 0);
                                                if (devOptsEnabled != 1) {
                                                    log("Developer options database is disabled. Enabling...");
                                                    android.provider.Settings.Global.putInt(
                                                        resolver, "development_settings_enabled", 1);
                                                }
                                                
                                                // 3. Set System Properties for Developer Options
                                                String devProp = (String) XposedHelpers.callStaticMethod(
                                                    sysProps, "get", "persist.sys.development_settings_enabled", "0");
                                                if (!"1".equals(devProp)) {
                                                    log("Forcing persist.sys.development_settings_enabled system property to 1");
                                                    XposedHelpers.callStaticMethod(sysProps, "set", 
                                                        "persist.sys.development_settings_enabled", "1");
                                                }
                                                
                                                // 4. Force USB configs to contain adb
                                                String usbProp = (String) XposedHelpers.callStaticMethod(
                                                    sysProps, "get", "persist.sys.usb.config", "");
                                                if (!usbProp.contains("adb")) {
                                                    String targetUsb = usbProp.isEmpty() ? "mtp,adb" : usbProp + ",adb";
                                                    log("Forcing persist.sys.usb.config to " + targetUsb);
                                                    XposedHelpers.callStaticMethod(sysProps, "set", 
                                                        "persist.sys.usb.config", targetUsb);
                                                }
                                                
                                                String usbState = (String) XposedHelpers.callStaticMethod(
                                                    sysProps, "get", "sys.usb.config", "");
                                                if (!usbState.contains("adb")) {
                                                    String targetUsbState = usbState.isEmpty() ? "mtp,adb" : usbState + ",adb";
                                                    log("Forcing sys.usb.config to " + targetUsbState);
                                                    XposedHelpers.callStaticMethod(sysProps, "set", 
                                                        "sys.usb.config", targetUsbState);
                                                }
                                            } catch (Exception e) {
                                                log("Error in auto-adb background monitor loop: " + e.getMessage());
                                            }
                                            
                                            // Run every 15 seconds to keep it active
                                            Thread.sleep(15000);
                                        }
                                    } catch (InterruptedException ie) {
                                        log("Auto-adb background monitor interrupted");
                                    } catch (Exception e) {
                                        log("Error in auto-adb background thread: " + e.getMessage());
                                    }
                                }
                            }).start();
                        } else {
                            log("System context is null, cannot start auto-ADB monitor");
                        }
                    } catch (Exception e) {
                        log("Error getting system context: " + e.getMessage());
                    }
                }
            });
        } catch (Throwable t) {
            log("Failed to hook ActivityManagerService systemReady: " + t.getMessage());
        }
    }

    private void autoAllowAdb(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> handlerClass;
            try {
                handlerClass = XposedHelpers.findClass(
                    "com.android.server.adb.AdbDebuggingManager$AdbDebuggingHandler", lpparam.classLoader);
            } catch (XposedHelpers.ClassNotFoundError e) {
                handlerClass = XposedHelpers.findClass(
                    "com.android.server.AdbDebuggingManager$AdbDebuggingHandler", lpparam.classLoader);
            }
            
            XposedHelpers.findAndHookMethod(handlerClass, "startConfirmation",
                String.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String key = (String) param.args[0];
                        log("startConfirmation intercepted for key: " + key + ". Auto-allowing ADB connection...");
                        
                        try {
                            Object manager = XposedHelpers.getSurroundingThis(param.thisObject);
                            XposedHelpers.callMethod(manager, "allowUser", key, true);
                            param.setResult(null); // Skip showing the prompt dialog
                        } catch (Exception e) {
                            log("Error auto-allowing user key: " + e.getMessage());
                        }
                    }
                });
            log("✓ ADB auto-allow hook installed");
        } catch (Throwable t) {
            log("Failed to install ADB auto-allow hook: " + t.getMessage());
        }
    }

    private void forceAdbEnabledForSystem(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> settingsGlobal = XposedHelpers.findClass(
                "android.provider.Settings$Global", lpparam.classLoader);

            // Hook getInt(ContentResolver, String)
            XposedHelpers.findAndHookMethod(settingsGlobal, "getInt",
                "android.content.ContentResolver", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[1];
                        if ("adb_enabled".equals(key) || "development_settings_enabled".equals(key)) {
                            param.setResult(1);
                        }
                    }
                });

            // Hook getInt(ContentResolver, String, int)
            XposedHelpers.findAndHookMethod(settingsGlobal, "getInt",
                "android.content.ContentResolver", String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[1];
                        if ("adb_enabled".equals(key) || "development_settings_enabled".equals(key)) {
                            param.setResult(1);
                        }
                    }
                });

            // Hook getString(ContentResolver, String)
            XposedHelpers.findAndHookMethod(settingsGlobal, "getString",
                "android.content.ContentResolver", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[1];
                        if ("adb_enabled".equals(key) || "development_settings_enabled".equals(key)) {
                            param.setResult("1");
                        }
                    }
                });

            // Hook SystemProperties for system server
            Class<?> sysProp = XposedHelpers.findClass(
                "android.os.SystemProperties", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(sysProp, "get", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[0];
                        if ("persist.sys.development_settings_enabled".equals(key)) {
                            param.setResult("1");
                        } else if ("persist.sys.usb.config".equals(key) || "sys.usb.config".equals(key) || "sys.usb.state".equals(key)) {
                            String original = (String) param.getResult();
                            if (original == null || original.isEmpty()) {
                                param.setResult("mtp,adb");
                            } else if (!original.contains("adb")) {
                                param.setResult(original + ",adb");
                            }
                        }
                    }
                });

            XposedHelpers.findAndHookMethod(sysProp, "get", String.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[0];
                        if ("persist.sys.development_settings_enabled".equals(key)) {
                            param.setResult("1");
                        } else if ("persist.sys.usb.config".equals(key) || "sys.usb.config".equals(key) || "sys.usb.state".equals(key)) {
                            String original = (String) param.getResult();
                            if (original == null || original.isEmpty()) {
                                param.setResult("mtp,adb");
                            } else if (!original.contains("adb")) {
                                param.setResult(original + ",adb");
                            }
                        }
                    }
                });

            XposedHelpers.findAndHookMethod(sysProp, "getBoolean", String.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[0];
                        if ("persist.sys.development_settings_enabled".equals(key)) {
                            param.setResult(true);
                        }
                    }
                });

            XposedHelpers.findAndHookMethod(sysProp, "getInt", String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[0];
                        if ("persist.sys.development_settings_enabled".equals(key)) {
                            param.setResult(1);
                        }
                    }
                });

            log("✓ System server ADB force-enable hooks installed");
        } catch (Throwable t) {
            log("Failed to install system server force-enable hooks: " + t.getMessage());
        }
    }




    // =============================================
    // HOOK 1: Settings.Global — Hide ADB & DevOpts
    // =============================================
    private void hookSettingsGlobal(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> settingsGlobal = XposedHelpers.findClass(
                "android.provider.Settings$Global", lpparam.classLoader);

            // Hook getInt(ContentResolver, String) 
            XposedHelpers.findAndHookMethod(settingsGlobal, "getInt",
                "android.content.ContentResolver", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[1];
                        if (shouldHideGlobalInt(key)) {
                            param.setResult(0);
                            log("[Global.getInt] Hid: " + key + " → 0");
                        }
                    }
                });

            // Hook getInt(ContentResolver, String, int defValue)
            XposedHelpers.findAndHookMethod(settingsGlobal, "getInt",
                "android.content.ContentResolver", String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[1];
                        if (shouldHideGlobalInt(key)) {
                            param.setResult(0);
                            log("[Global.getInt+def] Hid: " + key + " → 0");
                        }
                    }
                });

            // Hook getString(ContentResolver, String)
            XposedHelpers.findAndHookMethod(settingsGlobal, "getString",
                "android.content.ContentResolver", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[1];
                        if (shouldHideGlobalInt(key)) {
                            param.setResult("0");
                            log("[Global.getString] Hid: " + key + " → '0'");
                        }
                    }
                });

            log("✓ Settings.Global hooks installed");

        } catch (Throwable t) {
            log("✗ Settings.Global hook failed: " + t.getMessage());
        }
    }

    // =============================================
    // HOOK 2: Settings.Secure — Hide Accessibility
    // =============================================
    private void hookSettingsSecure(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> settingsSecure = XposedHelpers.findClass(
                "android.provider.Settings$Secure", lpparam.classLoader);

            // Hook getInt(ContentResolver, String)
            XposedHelpers.findAndHookMethod(settingsSecure, "getInt",
                "android.content.ContentResolver", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[1];
                        if (shouldHideSecureInt(key)) {
                            param.setResult(0);
                            log("[Secure.getInt] Hid: " + key + " → 0");
                        }
                    }
                });

            // Hook getInt(ContentResolver, String, int defValue)
            XposedHelpers.findAndHookMethod(settingsSecure, "getInt",
                "android.content.ContentResolver", String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[1];
                        if (shouldHideSecureInt(key)) {
                            param.setResult(0);
                            log("[Secure.getInt+def] Hid: " + key + " → 0");
                        }
                    }
                });

            // Hook getString(ContentResolver, String) — for accessibility service list
            XposedHelpers.findAndHookMethod(settingsSecure, "getString",
                "android.content.ContentResolver", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[1];
                        if (shouldHideSecureString(key)) {
                            param.setResult("");
                            log("[Secure.getString] Hid: " + key + " → ''");
                        }
                        // Also hide accessibility_enabled via getString
                        if (shouldHideSecureInt(key)) {
                            param.setResult("0");
                            log("[Secure.getString] Hid: " + key + " → '0'");
                        }
                    }
                });

            log("✓ Settings.Secure hooks installed");

        } catch (Throwable t) {
            log("✗ Settings.Secure hook failed: " + t.getMessage());
        }
    }

    // =============================================
    // HOOK 3: Debug class — Hide debugger status
    // =============================================
    private void hookDebugClass(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook Debug.isDebuggerConnected() → always false
            XposedHelpers.findAndHookMethod(
                "android.os.Debug", lpparam.classLoader,
                "isDebuggerConnected",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(false);
                    }
                });

            // Hook Debug.waitingForDebugger() → always false
            XposedHelpers.findAndHookMethod(
                "android.os.Debug", lpparam.classLoader,
                "waitingForDebugger",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(false);
                    }
                });

            log("✓ Debug class hooks installed");

        } catch (Throwable t) {
            log("✗ Debug class hook failed: " + t.getMessage());
        }
    }

    // =============================================
    // HOOK 4: SystemProperties — Hide build props
    // =============================================
    private void hookSystemProperties(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> sysProp = XposedHelpers.findClass(
                "android.os.SystemProperties", lpparam.classLoader);

            // Hook get(String) 
            XposedHelpers.findAndHookMethod(sysProp, "get", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[0];
                        String spoofed = getSpoofedProp(key);
                        if (spoofed != null) {
                            param.setResult(spoofed);
                        }
                    }
                });

            // Hook get(String, String defValue)
            XposedHelpers.findAndHookMethod(sysProp, "get", 
                String.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[0];
                        String spoofed = getSpoofedProp(key);
                        if (spoofed != null) {
                            param.setResult(spoofed);
                        }
                    }
                });

            // Hook getBoolean(String, boolean)
            XposedHelpers.findAndHookMethod(sysProp, "getBoolean",
                String.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[0];
                        if ("ro.debuggable".equals(key) || "persist.sys.development_settings_enabled".equals(key)) {
                            param.setResult(false);
                        }
                    }
                });

            // Hook getInt(String, int)
            XposedHelpers.findAndHookMethod(sysProp, "getInt",
                String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[0];
                        if ("ro.debuggable".equals(key) || "persist.sys.development_settings_enabled".equals(key)) {
                            param.setResult(0);
                        }
                    }
                });


            log("✓ SystemProperties hooks installed");

        } catch (Throwable t) {
            log("✗ SystemProperties hook failed: " + t.getMessage());
        }
    }

    // =============================================
    // HELPER: Check if prop should be spoofed
    // =============================================
    private String getSpoofedProp(String key) {
        if (key == null) return null;
        switch (key) {
            case "ro.debuggable":       return "0";
            case "ro.secure":           return "1";
            case "ro.adb.secure":       return "1";
            case "service.adb.root":    return "0";
            case "ro.build.type":       return "user";
            case "persist.sys.development_settings_enabled": return "0";
            case "persist.sys.usb.config":
            case "sys.usb.config":
            case "sys.usb.state":
                return "mtp";
            case "init.svc.adbd":
                return "stopped";
            default: return null;
        }
    }

    // =============================================
    // HELPERS: Check which keys to hide
    // =============================================
    private boolean shouldHideGlobalInt(String key) {
        if (key == null) return false;
        for (String hideKey : GLOBAL_HIDE_INT_KEYS) {
            if (hideKey.equals(key)) return true;
        }
        return false;
    }

    private boolean shouldHideSecureInt(String key) {
        if (key == null) return false;
        for (String hideKey : SECURE_HIDE_INT_KEYS) {
            if (hideKey.equals(key)) return true;
        }
        return false;
    }

    private boolean shouldHideSecureString(String key) {
        if (key == null) return false;
        for (String hideKey : SECURE_HIDE_STRING_KEYS) {
            if (hideKey.equals(key)) return true;
        }
        return false;
    }

    // =============================================
    // LOGGING
    // =============================================
    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }
}
