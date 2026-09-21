# Keep the core engine: it is reached via interfaces the R8 tree-shaker cannot see.
-keep class dev.arenalite.core.** { *; }
-keepclassmembers class dev.arenalite.core.json.JsonValue { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
