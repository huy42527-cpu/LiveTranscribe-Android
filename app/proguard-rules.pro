# Vosk: preserve JNI native method names for JNA Native.register().
-keep class org.vosk.LibVosk {
    *;
}

# JNA: preserve classes and members used through reflection/native registration.
-keep class com.sun.jna.** {
    *;
}
# JNA references Java AWT classes that are unavailable on Android.
-dontwarn java.awt.**
