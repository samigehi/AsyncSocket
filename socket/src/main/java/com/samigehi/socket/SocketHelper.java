package com.samigehi.socket;

import com.samigehi.socket.callback.*;
import com.samigehi.socket.core.ByteBufferReader;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import static com.samigehi.socket.SocketServer.log;

public class SocketHelper {
    private String serverIp;
    private int port;
    private final String tag = "ServerHelper";
    private Cancellable request;
    private AsyncSocket socket;
    private boolean isClosed, isConnected = false;
    //var isEnded = false
    private SocketListener callback;
    private long timeout = 10000L;
    private Object scheduled = null;

    private static final SocketServer SERVER = new SocketServer("SocketServer");

    public static SocketHelper getDefault() {
        return new SocketHelper(Util.SOCKET_URL, Util.SOCKET_PORT);
    }

    public AsyncSocket getSocket() {
        return socket;
    }

    public SocketHelper(String host, int port) {
        serverIp = host;
        this.port = port;
    }

    public Cancellable connect(SocketListener callback) {
        log("connect callback >>");
        this.callback = callback;
        return connect();
    }

    private Cancellable connect() {
        SocketServer.setIsLoggable(true);

        if (this.isConnected && this.request != null) {
            return request;
        }

        log("connect >>");
        request = SERVER.connectSocket(new InetSocketAddress(serverIp, port), new ConnectCallback() {
            @Override
            public void onConnectCompleted(Exception ex, AsyncSocket socket_) {
                if (ex != null) {
                    ex.printStackTrace();
                    isConnected = false;
                    callback.onError(ex);
                    log("[$tag] error on connect");
                }
                if (socket == null) {
                    return;
                }
                // connected
                isConnected = true;
                isClosed = false;
                socket = socket_;
                callback.onConnect(socket_);
                socket.setDataCallback(new DataCallback() {
                    @Override
                    public void onDataAvailable(DataEmitter emitter, ByteBufferReader bb) {
                        // triggered when new data available from server
                        callback.onDataReceived(new String(bb.getAllByteArray()), null);
                        //notifyEvents(String(bb!!.allByteArray))
                        //log("[$tag] Received Message " + String(bb!!.allByteArray))
                    }
                });

                socket.setWriteableCallback(new WritableCallback() {
                    @Override
                    public void onWriteable() {
                        // when something write/sent to server
                    }
                });

                socket.setClosedCallback(new CompletedCallback() {
                    @Override
                    public void onCompleted(Exception ex) {
                        // when connection closed
                        isConnected = false;
                        //this.isClosed = true
                        callback.onClosed(ex);
                        log("[$tag] Successfully closed connection");
                        isClosed = true;
                    }
                });

                socket.setEndCallback(new CompletedCallback() {
                    @Override
                    public void onCompleted(Exception ex) {
                        // when connection ended
                        isConnected = false;
                        callback.onDisconnect(ex);
                        log("[$tag] Successfully end connection");
                        isClosed = true;
                    }
                });
            }
        });
        scheduled = SERVER.postDelayed(new Runnable() {
            @Override
            public void run() {
                log("$tag cancel request called");
                cancel();

            }
        }, timeout);

        return request;
    }


    public void send(String msg) {
        send(msg, 0L);
    }

    public void send(final String msg, final long delay) {
        log("msg >>"+msg+" >> connected "+isConnected);
        if (!isConnected)
            return;
        SERVER.postDelayed(new Runnable() {
            @Override
            public void run() {
                writeAll(socket, msg.getBytes(), new CompletedCallback() {
                    @Override
                    public void onCompleted(Exception ex) {
                        callback.onDataWrite(msg, ex);
                    }
                });
            }
        }, delay);
    }

    public void send(final String msg, final long delay, final CompletedCallback completedCallback) {
        log("msg >>"+msg+" >> connected "+isConnected);
        if (!isConnected)
            return;
        SERVER.postDelayed(new Runnable() {
            @Override
            public void run() {
                writeAll(socket, msg.getBytes(), new CompletedCallback() {
                    @Override
                    public void onCompleted(Exception ex) {
                        callback.onDataWrite(msg, ex);
                        if(completedCallback != null)
                            completedCallback.onCompleted(ex);
                    }
                });
            }
        }, delay);

    }


    public void disconnect() {
        if (!isConnected)
            return;
        try {
            SERVER.stop();
        } catch (Exception ignored) {

        }
        try {
            request.cancel();
        } catch (Exception ignored) {

        }
        this.isConnected = false;
    }

    public void reconnect() {
        if (isClosed)
            return;
        if (callback == null)
            throw new NullPointerException("callback is null");
        // if (!isConnected)
        connect();
    }

    private void cancel() {
        if (!isConnected && socket == null) {
            try {
                request.cancel();
                this.isConnected = false;
                if (scheduled != null) {
                    SERVER.removeAllCallbacks(scheduled);
                }
                callback.onClosed(null);
                log(tag+" request cancelled");

            } catch (Exception e) {
                e.printStackTrace();
                //log("$tag request not cancelled");
            }
        }
    }


    private void writeAll(final DataSink sink, final ByteBufferReader bb, final CompletedCallback callback) {
        WritableCallback wc = new WritableCallback() {
            @Override
            public void onWriteable() {
                sink.write(bb);
                if (bb.remaining() == 0 && callback != null) {
                    sink.setWriteableCallback(null);
                    callback.onCompleted(null);
                }
            }
        };
        sink.setWriteableCallback(wc);
        wc.onWriteable();
    }


    public void writeAll(DataSink sink, byte[] bytes, CompletedCallback callback) {
        if (sink != null) {
            ByteBuffer bb = ByteBufferReader.obtain(bytes.length);
            bb.put(bytes);
            bb.flip();
            ByteBufferReader bbl = new ByteBufferReader();
            bbl.add(bb);
            writeAll(sink, bbl, callback);
        }
    }
}
