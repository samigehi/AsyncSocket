package com.samigehi.socket.callback;


import com.samigehi.socket.SocketServer;

public interface AsyncSocket extends DataEmitter, DataSink {
    public SocketServer getServer();
}
