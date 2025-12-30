# ProGuard rules for AndroidX Benchmark
# Keep benchmark classes and methods identifiable in reports

# Keep benchmark class names
-keep class com.ditchoom.buffer.AndroidBufferBenchmark { *; }

# Keep all buffer classes for accurate benchmarking
-keep class com.ditchoom.buffer.** { *; }

# Don't obfuscate (required for benchmark reports to be readable)
-dontobfuscate
