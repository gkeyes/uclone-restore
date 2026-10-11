# UClone launcher module release shrinking (R8).
#
# The module is loaded by LSPosed inside com.miui.home: the entry class is named in
# META-INF/xposed/java_init.list and hooks reach launcher internals via reflection. Keep all
# module code whole; only library code (Kotlin stdlib) is shrunk.
-keep class com.uclone.restore.module.** { *; }
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# libxposed API is compileOnly (provided by LSPosed at runtime).
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.** { *; }
