# Rust JNI bridge.
# Rust exports symbols using this exact class and native method names.
-keepclasseswithmembernames class com.example.veilknit_deamon.NativeDaemonBridge {
    native <methods>;
}

# ONNX Runtime uses JNI lookups of Java classes/methods by their original names.
# Required for R8-minified release builds (see ONNX Runtime Android docs).
-keep class ai.onnxruntime.** { *; }
