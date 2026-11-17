# ---------------------------------------------------------------------------------------------------------------------------------------
# Release (R8) rules for Infinite Canvas Calculator.
#
# ONNX Runtime (the CoMER handwriting model) calls back into Java BY NAME from native code (JNI): class, field and method names must survive
# shrinking and renaming, otherwise recognition fails in a release build even though debug builds work. If these rules are missing the app still
# runs (it falls back to the built-in recogniser and logs "primary recogniser is not usable in this build"), but accuracy is much lower.
# ---------------------------------------------------------------------------------------------------------------------------------------
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep readable stack traces in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Nothing else needs keeping: Hilt, Room, Compose and the Kotlin libraries ship their own consumer rules, and the app uses no reflection of its own.
# After a release build run: python tools/check_release.py (it verifies that the names above survived).
