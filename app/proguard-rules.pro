# Sandrix Studio - R8 rules for the release build.

-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The IDE loads and drives these bundled toolchain libraries at runtime.
# Keep them intact so their internal reflection / service lookups keep working.
-keep class com.android.tools.r8.** { *; }
-keep class com.android.tools.build.apksig.** { *; }
-keep class org.eclipse.jdt.** { *; }
-keep class org.eclipse.jgit.** { *; }
-keep class org.slf4j.** { *; }
-keep class org.xmlpull.** { *; }

# ASM bytecode instrumentation redirects calls into this package by name.
-keep class com.example.sandrix.compat.** { *; }

# Entry point launched via app_process for crash-isolated R8 runs.
-keep class com.example.sandrix.engine.R8ChildMain { public static void main(java.lang.String[]); }

-dontwarn com.android.tools.**
-dontwarn org.eclipse.**
-dontwarn org.slf4j.**
-dontwarn org.objectweb.asm.**
-dontwarn java.lang.management.**
-dontwarn javax.**
-dontwarn sun.**
