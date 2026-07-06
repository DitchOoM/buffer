package com.ditchoom.buffer.flow

/**
 * Thrown from a typed [Receiver.receive] flow when the underlying byte stream is
 * [reset][ReadResult.Reset] by the peer before it completes cleanly. Distinguishes an abrupt reset
 * from a clean end-of-stream, which instead completes the flow.
 */
public class ByteStreamResetException : IllegalStateException("Byte stream was reset by the peer")
