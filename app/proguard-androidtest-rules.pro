# R8 config for the instrumentation test APK of the `minifiedTest` build type.
# The test APK itself is not shrunk/renamed; R8 only applies the app's mapping to it.
-dontshrink
-dontoptimize
-dontobfuscate
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.lang.model.**
-dontwarn org.junit.**
-dontwarn org.hamcrest.**
