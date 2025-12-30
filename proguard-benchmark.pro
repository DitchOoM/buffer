# ProGuard/R8 rules for AndroidX Benchmark
# Allow full R8 optimization - use mapping.txt to deobfuscate if needed

# Keep benchmark class and test method names for readable reports
-keepnames class com.ditchoom.buffer.AndroidBufferBenchmark
-keepclassmembernames class com.ditchoom.buffer.AndroidBufferBenchmark {
    @org.junit.Test <methods>;
}

# Keep AllocationZone enum names (used in benchmark setup, visible in reports)
-keepclassmembers enum com.ditchoom.buffer.AllocationZone { *; }
