# ProGuard/R8 rules for AndroidX Benchmark
# Allow R8 to optimize library code while keeping benchmark infrastructure readable

# Keep benchmark class and test methods
-keep class com.ditchoom.buffer.AndroidBufferBenchmark {
    @org.junit.Test <methods>;
}

# Keep public API entry points (factory methods)
-keep class com.ditchoom.buffer.PlatformBuffer {
    public static ** allocate(...);
}
-keep class com.ditchoom.buffer.AllocationZone { *; }
-keep class com.ditchoom.buffer.AllocationZone$* { *; }

# Don't obfuscate - keeps class/method names readable in benchmark reports and stack traces
-dontobfuscate
