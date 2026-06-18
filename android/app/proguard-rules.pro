# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class net.qmindtech.tmap.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class net.qmindtech.tmap.**$$serializer { *; }
-keep class net.qmindtech.tmap.**$Companion { *; }
