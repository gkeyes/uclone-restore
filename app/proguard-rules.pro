# UClone Restore release shrinking (R8).
#
# Strategy: shrink and optimize everything, but never rename our own classes or members.
# UClone is a data-safety-critical root tool: class/member names show up in task logs and crash
# traces, enum names are persisted, and one class runs in a separate uid-0 process launched by
# name. Library code (Compose, Material, icons, coroutines) is still shrunk and obfuscated.

# --- Our own code: keep names (unused code may still be removed) -----------------------------
-keepnames class com.uclone.** { *; }
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

# --- Enums persisted or parsed by name ------------------------------------------------------
# TaskStatus / TaskType / TaskStage are written to task JSON + markers and read back with
# valueOf(); SwitchSafetyMode / MainReturnPointPolicy / CloneSessionPolicy are stored in
# SharedPreferences by name(). Keep every UClone enum intact so stored names always resolve.
-keep enum com.uclone.** { *; }

# --- Root process (libsu RootService) -------------------------------------------------------
# CrossUserRootService runs in a uid-0 process that libsu starts with app_process and then
# instantiates by class name (component in the manifest). libsu's own bootstrap classes
# (RootServerMain etc.) are also loaded by name in that process.
-keep class com.uclone.crossuser.runtime.CrossUserRootService { *; }
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# --- Binder / AIDL / Parcelable contract (app <-> root process, same APK) --------------------
# The AIDL stubs/proxies and the @Parcelize models cross the process boundary; keep them whole
# so both sides always agree on transaction codes, descriptors and CREATOR fields.
-keep class com.uclone.crossuser.api.** { *; }
-keep interface com.uclone.crossuser.api.** { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# --- Reflection on platform hidden APIs ------------------------------------------------------
# crossuser-runtime reflects on framework classes only (InputManager.injectInputEvent,
# InputEvent.setDisplayId, DisplayManager flags); framework code is not processed by R8, so no
# rules are needed beyond keeping our own call sites' names (above).

# --- External entry points -----------------------------------------------------------------
# Activities, services, receivers and providers (ExternalActionActivity, ExternalActionService,
# LauncherShortcutActionActivity, CrossUserHostActivity) are kept by AGP from the merged
# manifest. Intents/extras between the app and the launcher module use string constants only.
