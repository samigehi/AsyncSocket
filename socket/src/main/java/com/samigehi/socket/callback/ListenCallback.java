package com.samigehi.socket.callback;

public interface ListenCallback extends CompletedCallback {
    public void onAccepted(AsyncSocket socket);
}
