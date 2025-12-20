package com.ditchoom.buffer

import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.CompressionLevel
import com.ditchoom.buffer.compression.compress
import com.ditchoom.buffer.compression.decompress
import com.ditchoom.buffer.compression.getOrThrow
import com.ditchoom.buffer.pool.createSafeBufferPool
import com.ditchoom.buffer.pool.createUnsafeBufferPool
import com.ditchoom.buffer.protocol.http.HttpMethod
import com.ditchoom.buffer.protocol.http.HttpParser
import com.ditchoom.buffer.protocol.mqtt.MqttPacketType
import com.ditchoom.buffer.protocol.mqtt.MqttParser
import com.ditchoom.buffer.protocol.mqtt.MqttPublish
import com.ditchoom.buffer.protocol.mqtt.MqttQos
import com.ditchoom.buffer.protocol.mqtt.MqttSerializer
import com.ditchoom.buffer.protocol.websocket.WebSocketParser
import com.ditchoom.buffer.protocol.websocket.WebSocketSerializer
import com.ditchoom.buffer.stream.BufferStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Comprehensive benchmark suite for the high-performance buffer API.
 *
 * Tests and measures:
 * 1. Buffer pool performance (Safe vs Unsafe)
 * 2. Protocol parser throughput (HTTP, WebSocket, MQTT)
 * 3. Compression performance
 * 4. Chunked streaming performance
 */
class HighPerformanceBufferBenchmark {
    // ========== Buffer Pool Benchmarks ==========

    @Test
    fun benchmarkSafeBufferPoolAllocation() =
        runTest {
            val pool = createSafeBufferPool(initialPoolSize = 16, maxPoolSize = 64)
            val iterations = 10000

            val time =
                measureTime {
                    repeat(iterations) {
                        val buffer = pool.acquire(1024)
                        buffer.writeByte(42)
                        buffer.release()
                    }
                }

            val stats = pool.stats()
            println("SafeBufferPool:")
            println("  Total allocations: ${stats.totalAllocations}")
            println("  Pool hits: ${stats.poolHits}")
            println("  Pool misses: ${stats.poolMisses}")
            println("  Time for $iterations iterations: $time")
            println("  Ops/sec: ${iterations * 1000 / time.inWholeMilliseconds}")

            assertTrue(stats.poolHits > 0, "Should have pool hits")
            pool.clear()
        }

    @Test
    fun benchmarkUnsafeBufferPoolAllocation() =
        runTest {
            val pool = createUnsafeBufferPool(initialPoolSize = 16, maxPoolSize = 64)
            val iterations = 10000

            val time =
                measureTime {
                    repeat(iterations) {
                        val buffer = pool.acquire(1024)
                        buffer.writeByte(42)
                        buffer.release()
                    }
                }

            val stats = pool.stats()
            println("UnsafeBufferPool:")
            println("  Total allocations: ${stats.totalAllocations}")
            println("  Pool hits: ${stats.poolHits}")
            println("  Pool misses: ${stats.poolMisses}")
            println("  Time for $iterations iterations: $time")
            println("  Ops/sec: ${iterations * 1000 / time.inWholeMilliseconds}")

            assertTrue(stats.poolHits > 0, "Should have pool hits")
            pool.clear()
        }

    @Test
    fun benchmarkPoolVsDirectAllocation() =
        runTest {
            val pool = createUnsafeBufferPool()
            val iterations = 5000
            val bufferSize = 4096

            // Pooled allocation
            val pooledTime =
                measureTime {
                    repeat(iterations) {
                        val buffer = pool.acquire(bufferSize)
                        // Simulate some work
                        buffer.writeInt(it)
                        buffer.release()
                    }
                }

            // Direct allocation using withUnsafeBuffer
            val directTime =
                measureTime {
                    repeat(iterations) {
                        withUnsafeBuffer(bufferSize, ByteOrder.BIG_ENDIAN) { buffer ->
                            buffer.writeInt(it)
                        }
                    }
                }

            println("Allocation comparison ($iterations iterations, ${bufferSize}B buffers):")
            println("  Pooled allocation: $pooledTime")
            println("  Direct allocation: $directTime")
            println("  Pool speedup: ${directTime.inWholeNanoseconds.toDouble() / pooledTime.inWholeNanoseconds}x")

            pool.clear()
        }

    // ========== HTTP Parser Benchmarks ==========

    @Test
    fun benchmarkHttpRequestParsing() =
        runTest {
            val pool = createUnsafeBufferPool()
            val parser = HttpParser(pool)
            val iterations = 1000

            val request =
                """
                GET /api/users?page=1&limit=10 HTTP/1.1
                Host: example.com
                User-Agent: Benchmark/1.0
                Accept: application/json
                Content-Type: application/json
                Authorization: Bearer token123


                """.trimIndent().replace("\n", "\r\n").encodeToByteArray()

            val time =
                measureTime {
                    repeat(iterations) {
                        val parsed = parser.parseRequest(request)
                        assertEquals(HttpMethod.GET, parsed.method)
                    }
                }

            val bytesProcessed = request.size.toLong() * iterations
            val throughputMBs = bytesProcessed.toDouble() / 1024 / 1024 / time.inWholeMilliseconds * 1000

            println("HTTP Request Parsing:")
            println("  Iterations: $iterations")
            println("  Total time: $time")
            println("  Throughput: ${String.format("%.2f", throughputMBs)} MB/s")
            println("  Requests/sec: ${iterations * 1000 / time.inWholeMilliseconds}")

            pool.clear()
        }

    @Test
    fun benchmarkHttpResponseParsing() =
        runTest {
            val pool = createUnsafeBufferPool()
            val parser = HttpParser(pool)
            val iterations = 1000

            val bodyContent = "Hello, World!".repeat(100)
            val response =
                """
                HTTP/1.1 200 OK
                Content-Type: application/json
                Content-Length: ${bodyContent.length}
                Server: Benchmark/1.0
                Date: Mon, 01 Jan 2024 00:00:00 GMT

                $bodyContent
                """.trimIndent().replace("\n", "\r\n").encodeToByteArray()

            val time =
                measureTime {
                    repeat(iterations) {
                        val parsed = parser.parseResponse(response)
                        assertEquals(200, parsed.statusCode)
                    }
                }

            val bytesProcessed = response.size.toLong() * iterations
            val throughputMBs = bytesProcessed.toDouble() / 1024 / 1024 / time.inWholeMilliseconds * 1000

            println("HTTP Response Parsing:")
            println("  Iterations: $iterations")
            println("  Response size: ${response.size} bytes")
            println("  Total time: $time")
            println("  Throughput: ${String.format("%.2f", throughputMBs)} MB/s")

            pool.clear()
        }

    // ========== WebSocket Parser Benchmarks ==========

    @Test
    fun benchmarkWebSocketFrameParsing() =
        runTest {
            val pool = createUnsafeBufferPool()
            val parser = WebSocketParser(pool)
            val serializer = WebSocketSerializer(pool)
            val iterations = 5000

            // Create a text frame with payload
            val payload = "Hello, WebSocket!".repeat(10)
            val frame = serializer.createTextFrame(payload, masked = true)

            val time =
                measureTime {
                    repeat(iterations) {
                        val parsed = parser.parseFrame(frame)
                        assertEquals(payload, (parsed as com.ditchoom.buffer.protocol.websocket.WebSocketFrame.Text).text)
                    }
                }

            val bytesProcessed = frame.size.toLong() * iterations
            val throughputMBs = bytesProcessed.toDouble() / 1024 / 1024 / time.inWholeMilliseconds * 1000

            println("WebSocket Frame Parsing:")
            println("  Iterations: $iterations")
            println("  Frame size: ${frame.size} bytes")
            println("  Total time: $time")
            println("  Throughput: ${String.format("%.2f", throughputMBs)} MB/s")
            println("  Frames/sec: ${iterations * 1000 / time.inWholeMilliseconds}")

            pool.clear()
        }

    @Test
    fun benchmarkWebSocketBinaryFrames() =
        runTest {
            val pool = createUnsafeBufferPool()
            val parser = WebSocketParser(pool)
            val serializer = WebSocketSerializer(pool)
            val iterations = 2000

            // Create a larger binary frame
            val payload = ByteArray(4096) { it.toByte() }
            val frame = serializer.createBinaryFrame(payload, masked = true)

            val time =
                measureTime {
                    repeat(iterations) {
                        val parsed = parser.parseFrame(frame)
                        assertTrue(parsed is com.ditchoom.buffer.protocol.websocket.WebSocketFrame.Binary)
                    }
                }

            val bytesProcessed = frame.size.toLong() * iterations
            val throughputMBs = bytesProcessed.toDouble() / 1024 / 1024 / time.inWholeMilliseconds * 1000

            println("WebSocket Binary Frame Parsing (4KB payloads):")
            println("  Iterations: $iterations")
            println("  Total time: $time")
            println("  Throughput: ${String.format("%.2f", throughputMBs)} MB/s")

            pool.clear()
        }

    // ========== MQTT Parser Benchmarks ==========

    @Test
    fun benchmarkMqttPublishParsing() =
        runTest {
            val pool = createUnsafeBufferPool()
            val parser = MqttParser(pool)
            val serializer = MqttSerializer(pool)
            val iterations = 5000

            // Create a PUBLISH packet
            val payload = "Hello, MQTT!".repeat(50).encodeToByteArray()
            val packet =
                MqttPublish(
                    remainingLength = 0, // Will be calculated
                    dup = false,
                    qos = MqttQos.AtLeastOnce,
                    retain = false,
                    topicName = "test/topic/benchmark",
                    packetId = 1234,
                    payload =
                        com.ditchoom.buffer.protocol.mqtt.MqttPayload
                            .Raw(payload),
                )
            val serialized = serializer.serialize(packet)

            val time =
                measureTime {
                    repeat(iterations) {
                        val parsed = parser.parsePacket(serialized)
                        assertEquals(MqttPacketType.Publish, parsed.packetType)
                    }
                }

            val bytesProcessed = serialized.size.toLong() * iterations
            val throughputMBs = bytesProcessed.toDouble() / 1024 / 1024 / time.inWholeMilliseconds * 1000

            println("MQTT PUBLISH Parsing:")
            println("  Iterations: $iterations")
            println("  Packet size: ${serialized.size} bytes")
            println("  Total time: $time")
            println("  Throughput: ${String.format("%.2f", throughputMBs)} MB/s")
            println("  Packets/sec: ${iterations * 1000 / time.inWholeMilliseconds}")

            pool.clear()
        }

    // ========== Compression Benchmarks ==========

    @Test
    fun benchmarkCompression() =
        runTest {
            val iterations = 500
            val data = "Hello, World! This is test data for compression benchmarking. ".repeat(100).encodeToByteArray()

            // Test different compression levels
            listOf(
                CompressionLevel.BestSpeed,
                CompressionLevel.Default,
                CompressionLevel.BestCompression,
            ).forEach { level ->
                val compressTime =
                    measureTime {
                        repeat(iterations) {
                            compress(data, CompressionAlgorithm.Deflate, level)
                        }
                    }

                val compressed = compress(data, CompressionAlgorithm.Deflate, level).getOrThrow()
                val ratio = data.size.toDouble() / compressed.size

                val decompressTime =
                    measureTime {
                        repeat(iterations) {
                            decompress(compressed, CompressionAlgorithm.Deflate)
                        }
                    }

                val compressThroughput = data.size.toLong() * iterations / 1024.0 / 1024.0 / compressTime.inWholeMilliseconds * 1000
                val decompressThroughput =
                    compressed.size.toLong() * iterations / 1024.0 / 1024.0 / decompressTime.inWholeMilliseconds * 1000

                println("Compression (${level::class.simpleName}):")
                println("  Original: ${data.size} bytes, Compressed: ${compressed.size} bytes")
                println("  Ratio: ${String.format("%.2f", ratio)}x")
                println("  Compress throughput: ${String.format("%.2f", compressThroughput)} MB/s")
                println("  Decompress throughput: ${String.format("%.2f", decompressThroughput)} MB/s")
            }
        }

    // ========== Chunked Streaming Benchmarks ==========

    @Test
    fun benchmarkChunkedHttpParsing() =
        runTest {
            val pool = createUnsafeBufferPool()
            val parser = HttpParser(pool)
            val iterations = 200

            // Create a large HTTP response
            val bodyContent = "X".repeat(65536) // 64KB body
            val response =
                """
                HTTP/1.1 200 OK
                Content-Type: text/plain
                Content-Length: ${bodyContent.length}

                $bodyContent
                """.trimIndent().replace("\n", "\r\n").encodeToByteArray()

            // Parse via chunked stream
            val time =
                measureTime {
                    repeat(iterations) {
                        val stream = BufferStream(response, chunkSize = 1024, pool)
                        val responses = parser.parseResponses(stream).toList()
                        assertEquals(1, responses.size)
                        assertEquals(200, responses[0].statusCode)
                    }
                }

            val bytesProcessed = response.size.toLong() * iterations
            val throughputMBs = bytesProcessed.toDouble() / 1024 / 1024 / time.inWholeMilliseconds * 1000

            println("Chunked HTTP Streaming (64KB responses, 1KB chunks):")
            println("  Iterations: $iterations")
            println("  Total time: $time")
            println("  Throughput: ${String.format("%.2f", throughputMBs)} MB/s")

            pool.clear()
        }

    // ========== Mixed Workload Benchmark ==========

    @Test
    fun benchmarkMixedProtocolWorkload() =
        runTest {
            val pool = createUnsafeBufferPool(maxPoolSize = 128)
            val httpParser = HttpParser(pool)
            val wsParser = WebSocketParser(pool)
            val wsSerializer = WebSocketSerializer(pool)
            val mqttParser = MqttParser(pool)
            val mqttSerializer = MqttSerializer(pool)
            val iterations = 1000

            // Prepare test data
            val httpRequest =
                "GET /api HTTP/1.1\r\nHost: test.com\r\n\r\n".encodeToByteArray()
            val wsFrame = wsSerializer.createTextFrame("Hello", masked = true)
            val mqttPacket =
                mqttSerializer.serialize(
                    MqttPublish(
                        remainingLength = 0,
                        dup = false,
                        qos = MqttQos.AtMostOnce,
                        retain = false,
                        topicName = "test",
                        packetId = null,
                        payload =
                            com.ditchoom.buffer.protocol.mqtt.MqttPayload
                                .Raw("data".encodeToByteArray()),
                    ),
                )

            val time =
                measureTime {
                    repeat(iterations) {
                        // Parse each protocol type
                        httpParser.parseRequest(httpRequest)
                        wsParser.parseFrame(wsFrame)
                        mqttParser.parsePacket(mqttPacket)
                    }
                }

            val totalBytes = (httpRequest.size + wsFrame.size + mqttPacket.size).toLong() * iterations
            val throughputMBs = totalBytes.toDouble() / 1024 / 1024 / time.inWholeMilliseconds * 1000

            val stats = pool.stats()
            println("Mixed Protocol Workload:")
            println("  Iterations: $iterations (3 protocols each)")
            println("  Total time: $time")
            println("  Throughput: ${String.format("%.2f", throughputMBs)} MB/s")
            println("  Pool stats: hits=${stats.poolHits}, misses=${stats.poolMisses}")
            println("  Pool hit rate: ${String.format("%.1f", stats.poolHits.toDouble() / stats.totalAllocations * 100)}%")

            pool.clear()
        }

    // ========== Memory Efficiency Benchmark ==========

    @Test
    fun benchmarkMemoryEfficiency() =
        runTest {
            val safePool = createSafeBufferPool(maxPoolSize = 32)
            val unsafePool = createUnsafeBufferPool(maxPoolSize = 32)
            val iterations = 10000

            // Safe pool
            repeat(iterations) {
                val buffer = safePool.acquire(1024)
                buffer.writeBytes(ByteArray(512) { it.toByte() })
                buffer.release()
            }
            val safeStats = safePool.stats()

            // Unsafe pool
            repeat(iterations) {
                val buffer = unsafePool.acquire(1024)
                buffer.writeBytes(ByteArray(512) { it.toByte() })
                buffer.release()
            }
            val unsafeStats = unsafePool.stats()

            println("Memory Efficiency ($iterations allocations):")
            println(
                "  SafePool: hits=${safeStats.poolHits}, misses=${safeStats.poolMisses}, " +
                    "peak=${safeStats.peakPoolSize}",
            )
            println(
                "  UnsafePool: hits=${unsafeStats.poolHits}, misses=${unsafeStats.poolMisses}, " +
                    "peak=${unsafeStats.peakPoolSize}",
            )

            safePool.clear()
            unsafePool.clear()
        }
}
