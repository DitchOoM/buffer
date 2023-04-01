// BufferIpcTestAidl.aidl
package com.ditchoom.buffer;

import com.ditchoom.buffer.JvmBuffer;

interface BufferIpcTestAidl {
    JvmBuffer aidl(int num, int type);
}