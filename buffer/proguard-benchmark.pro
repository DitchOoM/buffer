# ProGuard/R8 rules for AndroidX Benchmark
# Keep library classes while allowing R8 to optimize method bodies

# Keep all buffer library classes but allow R8 to optimize bytecode within methods
# - Prevents class/method removal (needed for benchmarks to call them)
# - Allows inlining, dead code elimination, and other optimizations within methods
-keep,allowoptimization class com.ditchoom.buffer.** { *; }

# Keep benchmark class and test method names for readable reports
-keepnames class com.ditchoom.buffer.AndroidBufferBenchmark
-keepclassmembernames class com.ditchoom.buffer.AndroidBufferBenchmark {
    @org.junit.Test <methods>;
}
