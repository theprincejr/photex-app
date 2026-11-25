# Preserve TDLib JNI Java API classes, callback handlers, and fields called from libtdjni.so.
-keep class org.drinkless.tdlib.** { *; }
-dontwarn org.drinkless.tdlib.**
