package com.samigehi.socket.callback;

public interface Cancellable {
    boolean isDone();

    boolean isCancelled();

    boolean cancel();
}
