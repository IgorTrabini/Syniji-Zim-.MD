# ProGuard / R8 keep rules for Syniji Zim converter .MD

# Preserve native JNI classes & callbacks for Kiwix / libzim and Zstandard
-keep class org.kiwix.libzim.** { *; }
-keep class org.kiwix.libkiwix.** { *; }
-keep class com.github.luben.zstd.** { *; }
-dontwarn org.kiwix.**
-dontwarn com.github.luben.zstd.**

# Preserve PDFBox-Android text extraction & BouncyCastle/font optional references
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.bouncycastle.**

# Preserve kotlinx.serialization models and serializers (Config, Audit, Navigation routes)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keep,includedescriptorclasses class com.example.model.** { *; }
-keep,includedescriptorclasses class com.example.ui.*Route* { *; }

# Preserve Room database & entities
-keep class * extends androidx.room.RoomDatabase
-keep class com.example.data.** { *; }
