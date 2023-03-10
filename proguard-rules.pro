-keepclassmembers class com.darkyen.sqlitelite.SQLiteNative {
  native <methods>; # either whole class must be gone or all native methods must be present to prevent failed linking
}
-keep class com.darkyen.sqlitelite.SQLiteInterruptedException # created from native code
-keepattributes Exceptions,InnerClasses
