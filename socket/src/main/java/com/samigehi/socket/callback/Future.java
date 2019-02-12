package com.samigehi.socket.callback;

public interface Future<T> extends Cancellable, java.util.concurrent.Future<T> {

    public Future<T> setCallback(FutureCallback<T> callback);

    public <C extends FutureCallback<T>> C then(C callback);

}
