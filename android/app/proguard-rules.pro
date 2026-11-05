# gomobile: the Go side calls these classes through JNI by name.
-keep class go.** { *; }
-keep class io.mygo.clash.libmygo.** { *; }
-keep class * implements io.mygo.clash.libmygo.Platform { *; }
-keep class * implements io.mygo.clash.libmygo.LineListener { *; }
