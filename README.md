
# AsyncSocket
Asynchronous socket (client+server) continues communications

A java library which used [SocketChannels](http://docs.oracle.com/javase/1.5.0/docs/api/java/nio/channels/SocketChannel.html) to communicate with server in non-blocking way I/O endpoints and totally based on [NIO](http://docs.oracle.com/javase/1.4.2/docs/api/java/nio/package-summary.html), used Buffer to send and retrieve data from server. Open multiple connections at once.



#### Features

* Based on NIO. One thread, driven by callbacks. Highly efficient.
* All operations return a Future that can be cancelled
* Socket client + socket server
* HTTP client + server
* Used java Socket and SocketChannel (non-blocking way)
* Fast and continues emit data from server when available
* Thread optimized, easy to use, Byte Buffer optimized
* ~65KB


# USAGE

## Import

```gradle
dependencies {
  implementation 'com.samigehi:socket:1.0'
}
```

*Java*

connect using helper class (easy and recommended)

 ```
  private final SocketHelper helper = new SocketHelper("127.0.0.1", 1236);
   
   
// later...
// check if connected or wifi-on
 helper.connect(new SocketListener() {

            @Override
            public void onConnect(AsyncSocket socket) {
                // on successfully connected to server
            }

            @Override
            public void onError(Exception error) {
                // when an error occurred
                error.printStackTrace();
            }

            @Override
            public void onClosed(Exception error) {
                // when connection closed by server or an error occurred to forcefully close server connection
            }

            @Override
            public void onDisconnect(Exception error) {
                // when connection closed by server or self closed by calling disconnect
            }

            @Override
            public void onDataWrite(String message, Exception error) {
                // notify when data successfully sent to server
                Log.d("SocketHelper", "onDataWrite >> " + message);
                if (error != null)
                    error.printStackTrace();
            }

            @Override
            public void onDataReceived(String message, DataEmitter emitter) {
                // notify when new data received from server
                Log.d("SocketHelper", "onDataReceived >> " + message);
                if (message.startsWith("~Login")) {
                    // Request  LOGIN
                } else if (message.startsWith("~OTP")) {
                    // Request OTP
                } else if (message.startsWith("...")) {
                    // Request any?
                }
            }
        });

```


Send/Write data to server from anywhere throught app using sinleton

```
// delay in milis
helper.send("~LOGIN|ABCD|EFG|h|$$", delay);
```




#### OR 
connect using base SocketServer class (for advance users)

```
final SocketServer server = SocketServer.getDefault();
        server.connectSocket("127.0.0.1", 8080, new ConnectCallback() {
            @Override
            public void onConnectCompleted(Exception ex, AsyncSocket socket) {
                if (ex != null) {
                    ex.printStackTrace();
                }
                if (socket == null) {
                    //not connected yet return
                    return;
                }
                // connected
                socket.setDataCallback(new DataCallback() {
                    @Override
                    public void onDataAvailable(DataEmitter emitter, ByteBufferReader bb) {
                        // triggered when new data available from server
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
                    }
                });

                socket.setEndCallback(new CompletedCallback() {
                    @Override
                    public void onCompleted(Exception ex) {
                        // when connection ended
                    }
                });
            }
        });
        
        
        
        // later write to server.....
         SocketServer.getDefault().post(new Runnable() {
            @Override
            public void run() {
                // white data to server
                Util.writeAll(socketRef, dataToWrite, new CompletedCallback() {
                    @Override
                    public void onCompleted(Exception ex) {

                    }
                });
            }
        });

```

This library is typical for those applications which require continues client-server communications 


# THANKS

Thanks to @Koush



