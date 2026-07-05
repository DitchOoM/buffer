package com.ditchoom.buffer.ktor

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.test.protocols.simple.Command
import com.ditchoom.buffer.codec.test.protocols.simple.CommandCodec
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Test group E: an embedded Ktor server round-trips a `@ProtocolMessage` value through the
 * [BufferCodecConverter] end to end. The server decodes the request body via the converter
 * (`call.receive`) and encodes the response via the converter (`call.respond`); the client sends
 * and reads raw codec-encoded bytes.
 */
class ContentConverterServerTest {
    private fun Command.encoded(): ByteArray {
        val buffer = CommandCodec.encodeToPlatformBuffer(this, BufferFactory.Default)
        return buffer.copyToByteArray(buffer.remaining())
    }

    private fun ByteArray.decodedCommand(): Command = CommandCodec.decode(BufferFactory.Default.wrap(this), DecodeContext.Empty)

    private fun roundTrip(message: Command) =
        testApplication {
            install(ContentNegotiation) {
                register(ContentType.Application.OctetStream, BufferCodecConverter(CommandCodec))
            }
            routing {
                post("/echo") {
                    val received = call.receive<Command>()
                    call.respond(received)
                }
            }

            val response =
                client.post("/echo") {
                    contentType(ContentType.Application.OctetStream)
                    setBody(message.encoded())
                }
            assertEquals(message, response.readRawBytes().decodedCommand())
        }

    @Test
    fun exactSizeVariant_roundTripsThroughServer() = roundTrip(Command.Ping(ts = 0x0102_0304_0506_0708L))

    @Test
    fun backPatchVariant_roundTripsThroughServer() = roundTrip(Command.Echo(msg = "server round-trip 🌍"))
}
