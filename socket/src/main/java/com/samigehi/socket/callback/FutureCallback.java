package com.samigehi.socket.callback;

public interface FutureCallback<T> {
    public void onCompleted(Exception e, T result);
}
