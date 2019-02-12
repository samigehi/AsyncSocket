package com.samigehi.socket.callback;

import com.samigehi.socket.SocketServer;
import com.samigehi.socket.core.ByteBufferReader;

public interface DataSink {
    public void write(ByteBufferReader bb);

    public WritableCallback getWriteableCallback();

    public void setWriteableCallback(WritableCallback handler);

    public boolean isOpen();

    public void end();

    public CompletedCallback getClosedCallback();

    public void setClosedCallback(CompletedCallback handler);

    public SocketServer getServer();
}
