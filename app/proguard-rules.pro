# ToBeVPN ProGuard Rules

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# libv2ray (Go/gomobile bindings)
-keep class libv2ray.** { *; }
-keep class go.** { *; }

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.tobevpn.app.data.remote.dto.** { *; }
-keep class com.tobevpn.app.data.remote.ExchangeRateResponse { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keep class com.google.gson.** { *; }
-keepattributes *Annotation*

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }

# Coroutines
-dontwarn kotlinx.coroutines.**

# Hilt
-dontwarn dagger.hilt.**

# ML Kit Code Scanner
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_code_scanner.** { *; }

# SQLCipher
-keep class net.zetetic.database.** { *; }

# Strip all log calls from release. Play Vitals captures uncaught exceptions
# directly from the runtime, not via Log.* calls, so removing Log.w/Log.e is
# safe and prevents accidental PII / endpoint leaks via logcat on release builds.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
