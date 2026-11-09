# Keep data models
-keep class com.shihabkt.ktsdialer.CallLogModel { *; }
-keep class com.shihabkt.ktsdialer.CallLogItem { *; }
-keep class com.shihabkt.ktsdialer.ContactModel { *; }
-keep class com.shihabkt.ktsdialer.SocialMessenger** { *; }
-keep class com.shihabkt.ktsdialer.AppUpdateManager** { *; }

# Keep view binding
-keepclassmembers class * implements androidx.viewbinding.ViewBinding {
    public static * inflate(...);
    public static * bind(...);
    public * getRoot();
}

# Keep Material Components and AndroidX
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# Strip unnecessary logging in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
