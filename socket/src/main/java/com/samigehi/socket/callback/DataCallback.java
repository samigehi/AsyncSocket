package com.samigehi.socket.callback;

import com.samigehi.socket.core.ByteBufferReader;

public interface DataCallback {
    public void onDataAvailable(DataEmitter emitter, ByteBufferReader bb);
}
