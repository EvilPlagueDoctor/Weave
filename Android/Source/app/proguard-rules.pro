# Rust JNI bridge.
# Rust exports symbols using this exact class and native method names.
-keepclasseswithmembernames class com.example.veilknit_deamon.NativeDaemonBridge {
    native <methods>;
}
