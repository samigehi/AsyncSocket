package com.samigehi.socket.core;

import android.util.Log;
import com.samigehi.socket.*;
import com.samigehi.socket.callback.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class AsyncNetworkSocket implements AsyncSocket {
    public AsyncNetworkSocket() {
    }

    @Override
    public void end() {
        mChannel.shutdownOutput();
    }

    public boolean isChunked() {
        return mChannel.isChunked();
    }

    private InetSocketAddress socketAddress;

    public void attach(SocketChannel channel, InetSocketAddress socketAddress) throws IOException {
        this.socketAddress = socketAddress;
        allocator = new Allocator();
        mChannel = new SocketChannelWrapper(channel);
    }

    public void attach(DatagramChannel channel) throws IOException {
        mChannel = new DatagramChannelWrapper(channel);
        // keep udp at roughly the mtu, which is 1540 or something
        // letting it grow freaks out nio apparently.
        allocator = new Allocator(8192);
    }

    ChannelWrapper getChannel() {
        return mChannel;
    }

    private ChannelWrapper mChannel;
    private SelectionKey mKey;
    private SocketServer mServer;

    public void setup(SocketServer server, SelectionKey key) {
        mServer = server;
        mKey = key;
    }

    @Override
    public void write(final ByteBufferReader list) {
        if (mServer.getAffinity() != Thread.currentThread()) {
            mServer.run(new Runnable() {
                @Override
                public void run() {
                    write(list);
                }
            });
            return;
        }
        if (!mChannel.isConnected()) {
            assert !mChannel.isChunked();
            return;
        }

        try {
            //int before = list.remaining();
            ByteBuffer[] arr = list.getAllArray();
            mChannel.write(arr);
            list.addAll(arr);
            handleRemaining(list.remaining());
            //mServer.onDataSent(before - list.remaining());
        } catch (IOException e) {
            closeInternal();
            reportEndPending(e);
            reportClose(e);
        }
    }

    private void handleRemaining(int remaining) throws IOException {
        if (!mKey.isValid())
            throw new IOException(new CancelledKeyException());
        if (remaining > 0) {
            // chunked channels should not fail
            assert !mChannel.isChunked();
            // register for a write notification if a write fails
            // turn write on
            mKey.interestOps(SelectionKey.OP_WRITE | mKey.interestOps());
        } else {
            // turn write off
            mKey.interestOps(~SelectionKey.OP_WRITE & mKey.interestOps());
        }
    }

    private ByteBufferReader pending = new ByteBufferReader();
//    private ByteBuffer[] buffers = new ByteBuffer[8];

    public void write() {
//        assert mWriteableHandler != null;
        if (!mChannel.isChunked()) {
            // turn write off
            mKey.interestOps(~SelectionKey.OP_WRITE & mKey.interestOps());
        }
        if (mWriteableHandler != null)
            mWriteableHandler.onWriteable();
    }


    private Allocator allocator;

    public void read() {
        emitPending();
        // even if the socket is paused,
        // it may end up getting a queued readable event if it is
        // already in the selector's ready queue.
        if (mPaused)
            return;
        //int total = 0;
        try {
            boolean closed = false;

//            ByteBufferReader.obtainArray(buffers, Math.min(Math.max(mToAlloc, 2 << 11), maxAlloc));
            ByteBuffer b = allocator.allocate();

            // keep track of the max mount read during this read cycle
            // so we can be quicker about allocations during the next
            // time this socket reads.
            long read = mChannel.read(b);
            if (read < 0) {
                closeInternal();
                closed = true;
            }
//                else {
//                    total += read;
//                }
            if (read > 0) {
                allocator.track(read);
                b.flip();
//                for (int i = 0; i < buffers.length; i++) {
//                    ByteBuffer b = buffers[i];
//                    buffers[i] = null;
//                    b.flip();
//                    pending.add(b);
//                }
                pending.add(b);
                Util.emitAllData(this, pending);
                SocketServer.log("AsyncNetwork added " + b);
            } else {
                ByteBufferReader.reclaim(b);
            }

            if (closed) {
                reportEndPending(null);
                reportClose(null);
            }
        } catch (Exception e) {
            closeInternal();
            reportEndPending(e);
            reportClose(e);
        }

        //return total;
    }

    private boolean closeReported;

    private void reportClose(Exception e) {
        if (closeReported)
            return;
        closeReported = true;
        if (mClosedHander != null) {
            mClosedHander.onCompleted(e);
            mClosedHander = null;
        }
    }

    @Override
    public void close() {
        closeInternal();
        reportClose(null);
    }

    private void closeInternal() {
        mKey.cancel();
        try {
            mChannel.close();
        } catch (IOException e) {
        }
    }

    private WritableCallback mWriteableHandler;

    @Override
    public void setWriteableCallback(WritableCallback handler) {
        mWriteableHandler = handler;
    }

    private DataCallback mDataHandler;

    @Override
    public void setDataCallback(DataCallback callback) {
        mDataHandler = callback;
    }

    @Override
    public DataCallback getDataCallback() {
        return mDataHandler;
    }

    private CompletedCallback mClosedHander;

    @Override
    public void setClosedCallback(CompletedCallback handler) {
        mClosedHander = handler;
    }

    @Override
    public CompletedCallback getClosedCallback() {
        return mClosedHander;
    }

    @Override
    public WritableCallback getWriteableCallback() {
        return mWriteableHandler;
    }

    private void reportEnd(Exception e) {
        if (mEndReported)
            return;
        mEndReported = true;
        if (mCompletedCallback != null)
            mCompletedCallback.onCompleted(e);
        else if (e != null) {
            Log.e("NIO", "Unhandled exception", e);
        }
    }

    private boolean mEndReported;
    private Exception mPendingEndException;

    private void reportEndPending(Exception e) {
        if (pending.hasRemaining()) {
            mPendingEndException = e;
            return;
        }
        reportEnd(e);
    }

    private CompletedCallback mCompletedCallback;

    @Override
    public void setEndCallback(CompletedCallback callback) {
        mCompletedCallback = callback;
    }

    @Override
    public CompletedCallback getEndCallback() {
        return mCompletedCallback;
    }

    @Override
    public boolean isOpen() {
        return mChannel.isConnected() && mKey.isValid();
    }

    private boolean mPaused = false;

    @Override
    public void pause() {
        if (mServer.getAffinity() != Thread.currentThread()) {
            mServer.run(new Runnable() {
                @Override
                public void run() {
                    pause();
                }
            });
            return;
        }

        if (mPaused)
            return;

        mPaused = true;
        try {
            mKey.interestOps(~SelectionKey.OP_READ & mKey.interestOps());
        } catch (Exception ex) {
        }
    }

    // emit all remaining data in queue
    private void emitPending() {
        if (pending.hasRemaining()) {
            Util.emitAllData(this, pending);
        }
    }

    @Override
    public void resume() {
        if (mServer.getAffinity() != Thread.currentThread()) {
            mServer.run(new Runnable() {
                @Override
                public void run() {
                    resume();
                }
            });
            return;
        }

        if (!mPaused)
            return;
        mPaused = false;
        try {
            mKey.interestOps(SelectionKey.OP_READ | mKey.interestOps());
        } catch (Exception ex) {
        }
        emitPending();
        if (!isOpen())
            reportEndPending(mPendingEndException);
    }

    @Override
    public boolean isPaused() {
        return mPaused;
    }

    @Override
    public SocketServer getServer() {
        return mServer;
    }


    public InetSocketAddress getRemoteAddress() {
        return socketAddress;
    }

    public int getLocalPort() {
        return mChannel.getLocalPort();
    }

    public Object getSocket() {
        return getChannel().getSocket();
    }

}