# JNI: the native libraries call these by name, so the shrinker must keep them and their
# natives. Everything else is reached by a visible call and R8 keeps it on its own.
-keepclasseswithmembers class com.layerbit.stave.native.** { native <methods>; }
-keep class com.layerbit.stave.native.** { *; }
# The project codec reads and writes JSON by hand (engine/project/ProjectJson.kt), never by
# reflection, so nothing in the engine needs keeping by name.
