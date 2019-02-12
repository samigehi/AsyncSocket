package com.samigehi.socket.callback;

import com.samigehi.socket.SocketServer;

public interface DataEmitter {
    public DataCallback getDataCallback();

    public void setDataCallback(DataCallback callback);

    public void pause();

    public void resume();

    public void close();

    public boolean isPaused();

    public CompletedCallback getEndCallback();

    public void setEndCallback(CompletedCallback callback);

    public SocketServer getServer();
}
