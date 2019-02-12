package com.samigehi.socket.callback;

public interface SocketListener {
        void onConnect(AsyncSocket socket);
        void onError(Exception error);
        void onClosed(Exception error);
        void onDisconnect(Exception error);
        void onDataWrite(String message, Exception error);
        void onDataReceived(String message, DataEmitter emitter);
    }