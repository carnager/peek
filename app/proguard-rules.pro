# Nothing reflective in the app itself; libraries ship their own consumer rules.

# WorkManager's Room database is created by reflection; R8 otherwise drops its constructor
# (crash at startup: NoSuchMethodException WorkDatabase_Impl.<init>).
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ONNX Runtime: its Java classes are reached from native code by name.
-keep class ai.onnxruntime.** { *; }
