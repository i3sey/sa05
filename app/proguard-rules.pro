# SA05 keep rules.
#
# The native executables (xray, ByeDPI, tun2socks) are launched with ProcessBuilder and are
# invisible to R8, so they need no rules. Everything below covers code that is reached by
# reflection and would otherwise be renamed or stripped.

# --- JNA ---------------------------------------------------------------------
# JNA resolves native symbols by the *method name* of the mapped interface, so both the
# library classes and every mapped interface must keep their names and members. Losing this
# breaks TG WS Proxy at runtime with an UnsatisfiedLinkError that no unit test would catch.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep interface com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Library { *; }
-keepclassmembers interface * extends com.sun.jna.Library { <methods>; }
-dontwarn java.awt.**

# --- WorkManager -------------------------------------------------------------
# Workers are instantiated reflectively from their class name.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# --- Diagnostics -------------------------------------------------------------
# Keep crash lines useful; the original source file name itself is not interesting.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
