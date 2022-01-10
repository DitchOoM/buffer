package com.ditchoom.buffer

import java.io.RandomAccessFile
import java.nio.ByteBuffer

class JvmBuffer(byteBuffer: ByteBuffer, fileRef: RandomAccessFile? = null) : BaseJvmBuffer(byteBuffer, fileRef)