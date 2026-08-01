# JNA resolves these classes, fields and methods by their original names from
# native code. Renaming Pointer.peer, for example, makes Native.initIDs fail
# before the Rust proxy library can be loaded.
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }

# Native.load maps these interface method names directly to exported Rust C
# symbols such as StartProxy and GetStats.
-keep interface com.tgwsproxy.android.ProxyLibrary { *; }

-dontwarn java.awt.**
