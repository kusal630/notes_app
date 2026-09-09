# Vellum release ProGuard / R8 rules.
# Referenced by the release build type in app/build.gradle.kts. Currently
# isMinifyEnabled = false, so these are dormant — they take effect as soon
# as minification is enabled, and F-Droid applies them on its builders.

# --- Generic: keep annotations/signatures R8 needs for reflection-based libs ---
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# --- Room (androidx.room:room-runtime 2.8.4) ---
# Generated AppDatabase_Impl plus entity/DAO metadata are accessed via reflection.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.DatabaseView class *
-keep class androidx.room.migration.** { *; }
-keep class com.vellum.notes.data.db.** { *; }
-dontwarn androidx.room.paging.**

# --- Vosk (com.alphacephei:vosk-android) ---
# JNI bridge: native methods and the org.vosk classes must survive shrinking.
-keep class org.vosk.** { *; }
-keepclassmembers class org.vosk.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Jetpack Compose / Material3 ---
# Composable functions are referenced by the compiler-generated state machinery.
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }
-dontwarn androidx.compose.**
# Keep remember/saveable state and navigation route classes in app code.
-keep class com.vellum.notes.ui.** { *; }

# --- kotlinx.serialization (plugin-managed serializers + JSON runtime) ---
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keepclasseswithmembers class com.vellum.notes.** {
    @kotlinx.serialization.Serializable <fields>;
}
-dontnote kotlinx.serialization.AnnotationsKt

# --- Coroutines / DataStore / WorkManager (service-loader + reflection) ---
-dontwarn kotlinx.coroutines.**
-dontwarn androidx.datastore.**
-dontwarn androidx.work.**
-keep class androidx.datastore.** { *; }
