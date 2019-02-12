package com.samigehi.socket;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import com.samigehi.socket.callback.*;
import com.samigehi.socket.core.Allocator;
import com.samigehi.socket.core.ByteBufferReader;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Copied from koush Async library
 *
 * @Modified By Sumeet.Gehi
 * */

public class Util {

    //@NotNull
    public static final String SOCKET_URL = "127.0.0.1";
    public static final int SOCKET_PORT = 8080;

    public static boolean isConnected(Context context) {
        ConnectivityManager c = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (c != null) {
            NetworkInfo n = c.getActiveNetworkInfo();
            return n != null && n.isConnectedOrConnecting();
        }

        return false;
    }

    public static void emitAllData(DataEmitter emitter, ByteBufferReader list) {
        //SocketServer.log("emitAllData start...");
        int remaining;
        DataCallback handler = null;
        //boolean SUPRESS_DEBUG_EXCEPTIONS = false;
        while (!emitter.isPaused() && (handler = emitter.getDataCallback()) != null && (remaining = list.remaining()) > 0) {
            handler.onDataAvailable(emitter, list);
            //SocketServer.log("emitAllData handler:onDataAvailable " + remaining);
            if (remaining == list.remaining() && handler == emitter.getDataCallback() && !emitter.isPaused()) {
                // this is generally indicative of failure...

                // 1) The data callback has not changed
                // 2) no data was consumed
                // 3) the data emitter was not paused

                // call byteBufferList.recycle() or read all the data to prevent this assertion.
                // this is nice to have, as it identifies protocol or parsing errors.

//                System.out.println("Data: " + list.peekString());
                SocketServer.log("emitAllData handler: " + handler);
                list.recycle();
                assert false;
                throw new RuntimeException("mDataHandler failed to consume data, yet remains the mDataHandler.");
            }
        }
        if (list.remaining() != 0 && !emitter.isPaused()) {
            // not all the data was consumed...
            // call byteBufferList.recycle() or read all the data to prevent this assertion.
            // this is nice to have, as it identifies protocol or parsing errors.
//            System.out.println("Data: " + list.peekString());
            SocketServer.log("emitAllData handler: " + handler);
            SocketServer.log("emitAllData emitter: " + emitter);
            list.recycle();
            assert false;
            throw new RuntimeException("Not all data was consumed by Util.emitAllData");
        }
        //SocketServer.log("emitAllData done...");
    }

    public static void pump(final InputStream is, final DataSink ds, final CompletedCallback callback) {
        pump(is, Integer.MAX_VALUE, ds, callback);
    }

    public static void pump(final InputStream is, final long max, final DataSink ds, final CompletedCallback callback) {
        final CompletedCallback wrapper = new CompletedCallback() {
            boolean reported;
            @Override
            public void onCompleted(Exception ex) {
                if (reported)
                    return;
                reported = true;
                callback.onCompleted(ex);
            }
        };

        final WritableCallback cb = new WritableCallback() {
            int totalRead = 0;
            private void cleanup() {
                ds.setClosedCallback(null);
                ds.setWriteableCallback(null);
                pending.recycle();
                Util.closeQuietly(is);
            }
            ByteBufferReader pending = new ByteBufferReader();
            Allocator allocator = new Allocator();

            @Override
            public void onWriteable() {
                try {
                    do {
                        if (!pending.hasRemaining()) {
                            ByteBuffer b = allocator.allocate();

                            long toRead = Math.min(max - totalRead, b.capacity());
                            int read = is.read(b.array(), 0, (int)toRead);
                            if (read == -1 || totalRead == max) {
                                cleanup();
                                wrapper.onCompleted(null);
                                return;
                            }
                            allocator.track(read);
                            totalRead += read;
                            b.position(0);
                            b.limit(read);
                            pending.add(b);
                        }
                        
                        ds.write(pending);
                    }
                    while (!pending.hasRemaining());
                }
                catch (Exception e) {
                    cleanup();
                    wrapper.onCompleted(e);
                }
            }
        };
        ds.setWriteableCallback(cb);

        ds.setClosedCallback(wrapper);
        
        cb.onWriteable();
    }
    
    private static void pump(final DataEmitter emitter, final DataSink sink, final CompletedCallback callback) {
        final DataCallback dataCallback = new DataCallback() {
            @Override
            public void onDataAvailable(DataEmitter emitter, ByteBufferReader bb) {
                sink.write(bb);
                if (bb.remaining() > 0)
                    emitter.pause();
            }
        };
        emitter.setDataCallback(dataCallback);
        sink.setWriteableCallback(new WritableCallback() {
            @Override
            public void onWriteable() {
                emitter.resume();
            }
        });

        final CompletedCallback wrapper = new CompletedCallback() {
            boolean reported;
            @Override
            public void onCompleted(Exception ex) {
                if (reported)
                    return;
                reported = true;
                emitter.setDataCallback(null);
                emitter.setEndCallback(null);
                sink.setClosedCallback(null);
                sink.setWriteableCallback(null);
                callback.onCompleted(ex);
            }
        };

        emitter.setEndCallback(wrapper);
        sink.setClosedCallback(new CompletedCallback() {
            @Override
            public void onCompleted(Exception ex) {
                if (ex == null)
                    ex = new IOException("sink was closed before emitter ended");
                wrapper.onCompleted(ex);
            }
        });
    }
    
    public static void stream(AsyncSocket s1, AsyncSocket s2, CompletedCallback callback) {
        pump(s1, s2, callback);
        pump(s2, s1, callback);
    }
    
    public static void pump(final File file, final DataSink ds, final CompletedCallback callback) {
        try {
            if (file == null || ds == null) {
                callback.onCompleted(null);
                return;
            }
            final InputStream is = new FileInputStream(file);
            pump(is, ds, new CompletedCallback() {
                @Override
                public void onCompleted(Exception ex) {
                    try {
                        is.close();
                        callback.onCompleted(ex);
                    }
                    catch (IOException e) {
                        callback.onCompleted(e);
                    }
                }
            });
        }
        catch (Exception e) {
            callback.onCompleted(e);
        }
    }

    private static void writeAll(final DataSink sink, final ByteBufferReader bb, final CompletedCallback callback) {
        WritableCallback wc;
        sink.setWriteableCallback(wc = new WritableCallback() {
            @Override
            public void onWriteable() {
                sink.write(bb);
                if (bb.remaining() == 0 && callback != null) {
                    sink.setWriteableCallback(null);
                    callback.onCompleted(null);
                }
            }
        });
        wc.onWriteable();
    }
    public static void writeAll(DataSink sink, byte[] bytes, CompletedCallback callback) {
        ByteBuffer bb = ByteBufferReader.obtain(bytes.length);
        bb.put(bytes);
        bb.flip();
        ByteBufferReader bbl = new ByteBufferReader();
        bbl.add(bb);
        writeAll(sink, bbl, callback);
    }

    public static void end(DataEmitter emitter, Exception e) {
        if (emitter == null)
            return;
        end(emitter.getEndCallback(), e);
    }

    public static void end(CompletedCallback end, Exception e) {
        if (end != null)
            end.onCompleted(e);
    }

    public static void writable(DataSink emitter) {
        if (emitter == null)
            return;
        writable(emitter.getWriteableCallback());
    }

    private static void writable(WritableCallback writable) {
        if (writable != null)
            writable.onWriteable();
    }


    // I/O operations


    private static void fastChannelCopy(final ReadableByteChannel src, final WritableByteChannel dest) throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(16 * 1024);
        while (src.read(buffer) != -1) {
            // prepare the buffer to be drained
            buffer.flip();
            // write to the channel, may block
            dest.write(buffer);
            // If partial transfer, shift remainder down
            // If buffer is empty, same as doing recycle()
            buffer.compact();
        }
        // EOF will leave buffer in fill state
        buffer.flip();
        // make sure the buffer is fully drained.
        while (buffer.hasRemaining()) {
            dest.write(buffer);
        }
    }

    public static void copyStream(InputStream input, OutputStream output) throws IOException
    {
        final ReadableByteChannel inputChannel = Channels.newChannel(input);
        final WritableByteChannel outputChannel = Channels.newChannel(output);
        // copy the channels
        fastChannelCopy(inputChannel, outputChannel);
    }

    private static byte[] readToEndAsArray(InputStream input) throws IOException
    {
        DataInputStream dis = new DataInputStream(input);
        byte[] stuff = new byte[1024];
        ByteArrayOutputStream buff = new ByteArrayOutputStream();
        int read = 0;
        while ((read = dis.read(stuff)) != -1)
        {
            buff.write(stuff, 0, read);
        }
        dis.close();
        return buff.toByteArray();
    }

    public static String readToEnd(InputStream input) throws IOException
    {
        return new String(readToEndAsArray(input));
    }

    static public String readFile(String filename) throws IOException {
        return readFile(new File(filename));
    }

    static public String readFileSilent(String filename) {
        try {
            return readFile(new File(filename));
        }
        catch (IOException e) {
            return null;
        }
    }

    private static String readFile(File file) throws IOException {
        byte[] buffer = new byte[(int) file.length()];
        DataInputStream input = null;
        try {
            input = new DataInputStream(new FileInputStream(file));
            input.readFully(buffer);
        } finally {
            closeQuietly(input);
        }
        return new String(buffer);
    }

    private static void writeFile(File file, String string) throws IOException {
        file.getParentFile().mkdirs();
        DataOutputStream dout = new DataOutputStream(new FileOutputStream(file));
        dout.write(string.getBytes());
        dout.close();
    }

    public static void writeFile(String filePath, String string) throws IOException {
        writeFile(new File(filePath), string);
    }

    static void closeQuietly(Closeable... closeables) {
        if (closeables == null)
            return;
        for (Closeable closeable : closeables) {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (IOException e) {
                    // http://stackoverflow.com/a/156525/9636
                }
            }
        }
    }

    public static void eat(InputStream input) throws IOException {
        byte[] stuff = new byte[1024];
        while (input.read(stuff) != -1);
    }
}
