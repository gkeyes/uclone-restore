# Extra rules for the `minifiedTest` build type ONLY (never applied to release).
#
# The Compose UI contract tests drive the minified app from a separate test APK. The test
# framework calls Compose/AndroidX internals and our UI/model types directly, which R8 could
# otherwise strip because the app itself never calls those exact members. Everything else in
# the app is shrunk exactly as in release.
-keep class com.uclone.restore.ui.** { *; }
-keep class com.uclone.restore.model.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.core.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlin.** { *; }
