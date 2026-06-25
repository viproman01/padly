# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class app.padly.android.**$$serializer { *; }
-keepclassmembers class app.padly.android.** {
    *** Companion;
}
-keepclasseswithmembers class app.padly.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Padly proto / wire layer — keep field names that the codec reads via reflection-free paths.
-keep class app.padly.android.proto.** { *; }
-keep class app.padly.android.transport.** { *; }
