# Proguard rules for CalmPad
-keepattributes *Annotation*
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
