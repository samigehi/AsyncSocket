/**
 * MIT License
 * <p>
 * Copyright (c) [2017] [Sumeet Gehi]
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.samigehi.socket;

import android.os.Handler;
//import android.support.annotation.NonNull;
import android.util.Log;
import com.samigehi.socket.callback.*;
import com.samigehi.socket.core.AsyncNetworkSocket;
import com.samigehi.socket.core.AsyncSemaphore;
import com.samigehi.socket.core.SelectorWrapper;
//import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SocketHelper class for seamless connection with socket server, used java Sockets and SocketChannels APis
 * Maintain continuous connectivity on background thread @serverThread throughout the app
 * <p>
 * Created By Sumeet.Gehi
 * <p>
 * github.com/samigehi
 *
 * @author Koush (ion)
 */

public class SocketServer {
    private static final String LOGTAG = "SocketServer-";
    private static boolean isLoggable = false;

    private final static WeakHashMap<Thread, SocketServer> servers = new WeakHashMap<Thread, SocketServer>();
    private static final Comparator<InetAddress> ipSorter = new Comparator<InetAddress>() {
        @Override
        public int compare(InetAddress lhs, InetAddress rhs) {
            if (lhs instanceof Inet4Address && rhs instanceof Inet4Address)
                return 0;
            if (lhs instanceof Inet6Address && rhs instanceof Inet6Address)
                return 0;
            if (lhs instanceof Inet4Address && rhs instanceof Inet6Address)
                return -1;
            return 1;
        }
    };
    private static final long QUEUE_EMPTY = Long.MAX_VALUE;
    private static SocketServer SERVER = new SocketServer();
    private static ExecutorService synchronousWorkers = newSynchronousWorkers("SocketServer-worker-");
    private static ExecutorService synchronousResolverWorkers = newSynchronousWorkers("SocketServer-resolver-");

    private String serverName;
    private int postCounter = 0;
    private PriorityQueue<Scheduled> mQueue = new PriorityQueue<Scheduled>(1, Scheduler.INSTANCE);
    private Thread serverThread;
    private SelectorWrapper mSelector;

    public SocketServer() {
        this(null);
    }

    public SocketServer(String name) {
        if (name == null)
            name = "SocketServer";
        serverName = name;
    }

    public static void post(Handler handler, Runnable runnable) {
        RunnableWrapper wrapper = new RunnableWrapper();
        ThreadQueue threadQueue = ThreadQueue.getOrCreateThreadQueue(handler.getLooper().getThread());
        wrapper.threadQueue = threadQueue;
        wrapper.handler = handler;
        wrapper.runnable = runnable;

        threadQueue.add(wrapper);
        handler.post(wrapper);

        // run the queue if the thread is blocking
        threadQueue.queueSemaphore.release();
    }

    public static SocketServer getDefault() {
        return SERVER;
    }

    private static void wakeup(final SelectorWrapper selector) {
        synchronousWorkers.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    selector.wakeupOnce();
                } catch (Exception e) {
                    log("Selector Exception? L Preview?");
                }
            }
        });
    }

    private static ExecutorService newSynchronousWorkers(String prefix) {
        return new ThreadPoolExecutor(1, 4, 10L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), new NamedThreadFactory(prefix));
    }

    public static SocketServer getCurrentThreadServer() {
        return servers.get(Thread.currentThread());
    }

    private static void run(final SocketServer server, final SelectorWrapper selector, final PriorityQueue<Scheduled> queue) {
        log("****SocketServer is starting.****");
        // at this point, this local queue and selector are owned by this thread.
        // if a stop is called, the instance queue and selector
        // will be replaced and nulled respectively.
        // this will allow the old queue and selector to shut down
        // gracefully, while also allowing a new selector thread
        // to start up while the old one is still shutting down.
        while (true) {
            try {
                runLoop(server, selector, queue);
            } catch (AsyncSelectorException e) {
                log("Selector exception, shutting down", e);
                try {
                    // Util.closeQuiety is throwing ArrayStoreException?
                    selector.getSelector().close();
                } catch (Exception ignore) {
                }
            }
            // see if we keep looping, this must be in a synchronized block since the queue is accessed.
            synchronized (server) {
                if (selector.isOpen() && (selector.keys().size() > 0 || queue.size() > 0))
                    continue;

                shutdownEverything(selector);
                if (server.mSelector == selector) {
                    server.mQueue = new PriorityQueue<Scheduled>(1, Scheduler.INSTANCE);
                    server.mSelector = null;
                    server.serverThread = null;
                }
                break;
            }
        }
        synchronized (servers) {
            servers.remove(Thread.currentThread());
        }
        log("****SocketServer has shut down.****");
    }

    private static void shutdownKeys(SelectorWrapper selector) {
        try {
            for (SelectionKey key : selector.keys()) {
                Util.closeQuietly(key.channel());
                try {
                    key.cancel();
                } catch (Exception e) {
                }
            }
        } catch (Exception ex) {
        }
    }

    private static void shutdownEverything(SelectorWrapper selector) {
        shutdownKeys(selector);
        // SHUT. DOWN. EVERYTHING.
        try {
            selector.close();
        } catch (Exception ignored) {
        }
    }

    private static long lockAndRunQueue(final SocketServer server, final PriorityQueue<Scheduled> queue) {
        long wait = QUEUE_EMPTY;

        // find the first item we can actually run
        while (true) {
            Scheduled run = null;

            synchronized (server) {
                long now = System.currentTimeMillis();

                if (queue.size() > 0) {
                    Scheduled s = queue.remove();
                    if (s.time <= now) {
                        run = s;
                    } else {
                        wait = s.time - now;
                        queue.add(s);
                    }
                }
            }

            if (run == null)
                break;

            run.runnable.run();
        }

        server.postCounter = 0;
        return wait;
    }

    private static void runLoop(final SocketServer server, final SelectorWrapper selector, final PriorityQueue<Scheduled> queue) throws AsyncSelectorException {
        //log("runLoop Keys: " + selector.keys().size());
        boolean needsSelect = true;

        // run the queue to populate the selector with keys
        long wait = lockAndRunQueue(server, queue);
        try {
            synchronized (server) {
                // select now to see if anything is ready immediately. this
                // also clears the canceled key queue.
                int readyNow = selector.selectNow();
                if (readyNow == 0) {
                    // if there is nothing to select now, make sure we don't have an empty key set
                    // which means it would be time to turn this thread off.
                    if (selector.keys().size() == 0 && wait == QUEUE_EMPTY) {
                        //log("Shutting down. keys: " + selector.keys().size());
                        //log("Shutting down. keys: " + selector.keys().size() + " keepRunning: " + keepRunning);
                        return;
                    }
                } else {
                    needsSelect = false;
                }
            }

            if (needsSelect) {
                if (wait == QUEUE_EMPTY) {
                    // wait until woken up
                    selector.select();
                } else {
                    // nothing to select immediately but there's something pending so let's block that duration and wait.
                    selector.select(wait);
                }
            }
        } catch (Exception e) {
            throw new AsyncSelectorException(e);
        }

        // process whatever keys are ready
        Set<SelectionKey> readyKeys = selector.selectedKeys();
        for (SelectionKey key : readyKeys) {
            log("readyKeys key >> " + key.readyOps());
            try {
                if (key.isAcceptable()) {
                    ServerSocketChannel nextReady = (ServerSocketChannel) key.channel();
                    SocketChannel sc = null;
                    SelectionKey ckey = null;
                    try {
                        sc = nextReady.accept();
                        if (sc == null)
                            continue;
                        sc.configureBlocking(false);
                        ckey = sc.register(selector.getSelector(), SelectionKey.OP_READ);
                        ListenCallback serverHandler = (ListenCallback) key.attachment();
                        AsyncNetworkSocket handler = new AsyncNetworkSocket();
                        handler.attach(sc, (InetSocketAddress) sc.socket().getRemoteSocketAddress());
                        handler.setup(server, ckey);
                        ckey.attach(handler);
                        serverHandler.onAccepted(handler);
                    } catch (IOException e) {
                        Util.closeQuietly(sc);
                        if (ckey != null)
                            ckey.cancel();
                    }
                } else if (key.isReadable()) {
                    AsyncNetworkSocket handler = (AsyncNetworkSocket) key.attachment();
                    handler.read();
                    //int transmitted = handler.read();
                    //server.onDataReceived(transmitted);
                } else if (key.isWritable()) {
                    AsyncNetworkSocket handler = (AsyncNetworkSocket) key.attachment();
                    handler.write();
                } else if (key.isConnectable()) {
                    ConnectFuture cancel = (ConnectFuture) key.attachment();
                    SocketChannel sc = (SocketChannel) key.channel();
                    key.interestOps(SelectionKey.OP_READ);
                    AsyncNetworkSocket newHandler;
                    try {
                        sc.finishConnect();
                        newHandler = new AsyncNetworkSocket();
                        newHandler.setup(server, key);
                        newHandler.attach(sc, (InetSocketAddress) sc.socket().getRemoteSocketAddress());
                        key.attach(newHandler);
                    } catch (IOException ex) {
                        key.cancel();
                        Util.closeQuietly(sc);
                        if (cancel.setComplete(ex))
                            cancel.callback.onConnectCompleted(ex, null);
                        continue;
                    }
                    try {
                        if (cancel.setComplete(newHandler))
                            cancel.callback.onConnectCompleted(null, newHandler);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    log("runloop wtf");
                    throw new RuntimeException("Unknown key state.");
                }
            } catch (CancelledKeyException ex) {
                log("runloop " + ex.getMessage());
            }
        }
        readyKeys.clear();
    }


    public boolean isRunning() {
        return mSelector != null;
    }

//    private void handleSocket(final AsyncSocket handler) throws ClosedChannelException {
//        final ChannelWrapper sc = handler.getChannel();
//        SelectionKey ckey = sc.register(mSelector.getSelector());
//        ckey.attach(handler);
//        handler.setup(this, ckey);
//    }

    public void removeAllCallbacks(Object scheduled) {
        synchronized (this) {
            mQueue.remove(scheduled);
        }
    }

    public Object postDelayed(Runnable runnable, long delay) {
        Scheduled s;
        synchronized (this) {
            // Calculate when to run this queue item:
            // If there is a delay (non-zero), add it to the current time
            // When delay is zero, ensure that this follows all other
            // zero-delay queue items. This is done by setting the
            // "time" to the queue size. This will make sure it is before
            // all time-delayed queue items (for all real world scenarios)
            // as it will always be less than the current time and also remain
            // behind all other immediately run queue items.
            long time;
            if (delay > 0)
                time = System.currentTimeMillis() + delay;
            else if (delay == 0)
                time = postCounter++;
            else if (mQueue.size() > 0)
                time = Math.min(0, mQueue.peek().time - 1);
            else
                time = 0;
            mQueue.add(s = new Scheduled(runnable, time));
            // start the SERVER up if necessary
            if (mSelector == null)
                run(true);
            if (!isAffinityThread()) {
                wakeup(mSelector);
            }
        }
        return s;
    }

    public Object post(Runnable runnable) {
        return postDelayed(runnable, 0);
    }

    public Object post(final CompletedCallback callback, final Exception e) {
        return post(new Runnable() {
            @Override
            public void run() {
                callback.onCompleted(e);
            }
        });
    }

    public void run(final Runnable runnable) {
        if (Thread.currentThread() == serverThread) {
            post(runnable);
            lockAndRunQueue(this, mQueue);
            return;
        }

        final Semaphore semaphore = new Semaphore(0);
        post(new Runnable() {
            @Override
            public void run() {
                runnable.run();
                semaphore.release();
            }
        });
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            log("run", e);
        }
    }

    public void stop() {
        log("****SocketServer is shutting down.****");
        final SelectorWrapper currentSelector;
        final Semaphore semaphore;
        final boolean isAffinityThread;
        synchronized (this) {
            isAffinityThread = isAffinityThread();
            currentSelector = mSelector;
            if (currentSelector == null)
                return;
            synchronized (servers) {
                servers.remove(serverThread);
            }
            semaphore = new Semaphore(0);

            // post a shutdown and wait
            mQueue.add(new Scheduled(new Runnable() {
                @Override
                public void run() {
                    shutdownEverything(currentSelector);
                    semaphore.release();
                }
            }, 0));
            currentSelector.wakeupOnce();

            // force any existing connections to die
            shutdownKeys(currentSelector);

            mQueue = new PriorityQueue<Scheduled>(1, Scheduler.INSTANCE);
            mSelector = null;
            serverThread = null;
        }
        try {
            if (!isAffinityThread)
                semaphore.acquire();
        } catch (Exception ignored) {
        }
    }

    void onDataReceived(int transmitted) {
        log("onDataReceived length " + transmitted);
    }

    void onDataSent(int transmitted) {
        log("onDataSent length " + transmitted);
    }

    // TODO Connect Socket Server
    private ConnectFuture connectResolvedInetSocketAddress(final InetSocketAddress address, final ConnectCallback callback) {
        final ConnectFuture cancel = new ConnectFuture();
        if (address.isUnresolved()) throw new AssertionError();

        post(new Runnable() {
            @Override
            public void run() {
                if (cancel.isCancelled())
                    return;

                cancel.callback = callback;
                SelectionKey ckey = null;
                SocketChannel socket = null;
                try {
                    socket = cancel.socket = SocketChannel.open();
                    socket.configureBlocking(false);
                    ckey = socket.register(mSelector.getSelector(), SelectionKey.OP_CONNECT);
                    ckey.attach(cancel);
                    socket.connect(address);
                } catch (Throwable e) {
                    if (ckey != null)
                        ckey.cancel();
                    Util.closeQuietly(socket);
                    cancel.setComplete(new RuntimeException(e));
                }
            }
        });

        return cancel;
    }

    public Cancellable connectSocket(final InetSocketAddress remote, final ConnectCallback callback) {
        if (!remote.isUnresolved())
            return connectResolvedInetSocketAddress(remote, callback);

        final SimpleFuture<AsyncNetworkSocket> ret = new SimpleFuture<>();

        Future<InetAddress> lookup = getByName(remote.getHostName());
        ret.setParent(lookup);
        lookup.setCallback(new FutureCallback<InetAddress>() {
            @Override
            public void onCompleted(Exception e, InetAddress result) {
                if (e != null) {
                    callback.onConnectCompleted(e, null);
                    ret.setComplete(e);
                    return;
                }

                ret.setComplete(connectResolvedInetSocketAddress(new InetSocketAddress(result, remote.getPort()), callback));
            }
        });
        return ret;
    }

    public Cancellable connectSocket(final String host, final int port, final ConnectCallback callback) {
        return connectSocket(InetSocketAddress.createUnresolved(host, port), callback);
    }

    public Future<InetAddress[]> getAllByName(final String host) {
        final SimpleFuture<InetAddress[]> ret = new SimpleFuture<InetAddress[]>();
        synchronousResolverWorkers.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final InetAddress[] result = InetAddress.getAllByName(host);
                    Arrays.sort(result, ipSorter);
                    if (result.length == 0)
                        throw new UnknownHostException("no addresses for host");
                    post(new Runnable() {
                        @Override
                        public void run() {
                            ret.setComplete(null, result);
                        }
                    });
                } catch (final Exception e) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            ret.setComplete(e, null);
                        }
                    });
                }
            }
        });
        return ret;
    }

    private Future<InetAddress> getByName(String host) {
        return getAllByName(host)
                .then(new TransformFuture<InetAddress, InetAddress[]>() {
                    @Override
                    protected void transform(InetAddress[] result) throws Exception {
                        setComplete(result[0]);
                    }
                });
    }

    private boolean addMe() {
        synchronized (servers) {
            SocketServer current = servers.get(serverThread);
            if (current != null) {
                log("****SocketServer already running on this thread.****");
                return false;
            }
            servers.put(serverThread, this);
        }
        return true;
    }

    private void run(boolean newThread) {
        final SelectorWrapper selector;
        final PriorityQueue<Scheduled> queue;
        boolean reentrant = false;
        synchronized (this) {
            if (mSelector != null) {
                log("Reentrant call");
                assert Thread.currentThread() == serverThread;
                // this is reentrant
                reentrant = true;
                selector = mSelector;
                queue = mQueue;
            } else {
                try {
                    selector = mSelector = new SelectorWrapper(SelectorProvider.provider().openSelector());
                    queue = mQueue;
                } catch (IOException e) {
                    return;
                }
                if (newThread) {
                    serverThread = new Thread(serverName) {
                        public void run() {
                            SocketServer.run(SocketServer.this, selector, queue);
                        }
                    };
                } else {
                    serverThread = Thread.currentThread();
                }
                if (!addMe()) {
                    try {
                        mSelector.close();
                    } catch (Exception ignored) {
                    }
                    mSelector = null;
                    serverThread = null;
                    return;
                }
                if (newThread) {
                    serverThread.start();
                    // kicked off the new thread, let's bail.
                    return;
                }

                // fall through to outside of the synchronization scope
                // to allow the thread to run without locking.
            }
        }

        if (reentrant) {
            try {
                runLoop(this, selector, queue);
            } catch (AsyncSelectorException e) {
                log("Selector closed", e);
                try {
                    // Util.closeQuiety is throwing ArrayStoreException?
                    selector.getSelector().close();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            return;
        }

        run(this, selector, queue);
    }


    @Override
    public String toString() {
        StringBuilder selector = new StringBuilder();
        if (mSelector != null)
            for (SelectionKey key : mSelector.keys()) {
                selector.append("Key: ").append(key).append("; ");
            }
        return "SocketServer{" +
                "serverName='" + serverName + '\'' +
                ", postCounter=" + postCounter +
                ", mQueue=" + mQueue +
                ", serverThread=" + serverThread +
                ", mSelector=" + mSelector +
                ", mSelectorKeys=" + selector.toString() +
                '}';
    }

    public void print() {
        post(new Runnable() {
            @Override
            public void run() {
                log("Server Name " + serverName);
                if (mSelector == null) {
                    log("Server dump not possible. No selector?");
                    return;
                }
                log("Key Count: " + mSelector.keys().size());

                for (SelectionKey key : mSelector.keys()) {
                    log("Key: " + key);
                }
            }
        });
    }

    public Thread getAffinity() {
        return serverThread;
    }

    private boolean isAffinityThread() {
        return serverThread == Thread.currentThread();
    }

    public boolean isAffinityThreadOrStopped() {
        Thread affinity = serverThread;
        return affinity == null || affinity == Thread.currentThread();
    }

    private static class RunnableWrapper implements Runnable {
        boolean hasRun;
        Runnable runnable;
        ThreadQueue threadQueue;
        Handler handler;

        @Override
        public void run() {
            synchronized (this) {
                if (hasRun)
                    return;
                hasRun = true;
            }
            try {
                runnable.run();
            } finally {
                threadQueue.remove(this);
                handler.removeCallbacks(this);

                threadQueue = null;
                handler = null;
                runnable = null;
            }
        }
    }

    private static class Scheduled {
        public Runnable runnable;
        public long time;

        Scheduled(Runnable runnable, long time) {
            this.runnable = runnable;
            this.time = time;
        }
    }

    static class Scheduler implements Comparator<Scheduled> {
        static Scheduler INSTANCE = new Scheduler();

        private Scheduler() {
        }

        @Override
        public int compare(Scheduled s1, Scheduled s2) {
            // keep the smaller ones at the head, so they get tossed out quicker
            if (s1.time == s2.time)
                return 0;
            if (s1.time > s2.time)
                return 1;
            return -1;
            //long compare require min 19
            //return Long.compare(s1.time, s2.time);
        }
    }

    private static class AsyncSelectorException extends IOException {
        AsyncSelectorException(Exception e) {
            super(e);
        }
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        NamedThreadFactory(String namePrefix) {
            SecurityManager s = System.getSecurityManager();
            group = (s != null) ? s.getThreadGroup() :
                    Thread.currentThread().getThreadGroup();
            this.namePrefix = namePrefix;
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r,
                    namePrefix + threadNumber.getAndIncrement(), 0);
            if (t.isDaemon()) t.setDaemon(false);
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    }

    public static class ThreadQueue extends LinkedList<Runnable> {
        final private static WeakHashMap<Thread, ThreadQueue> mThreadQueues = new WeakHashMap<Thread, ThreadQueue>();
        AsyncSemaphore waiter;
        Semaphore queueSemaphore = new Semaphore(0);

        static ThreadQueue getOrCreateThreadQueue(Thread thread) {
            ThreadQueue queue;
            synchronized (mThreadQueues) {
                queue = mThreadQueues.get(thread);
                if (queue == null) {
                    queue = new ThreadQueue();
                    mThreadQueues.put(thread, queue);
                }
            }

            return queue;
        }

        static void release(AsyncSemaphore semaphore) {
            synchronized (mThreadQueues) {
                for (ThreadQueue threadQueue : mThreadQueues.values()) {
                    if (threadQueue.waiter == semaphore)
                        threadQueue.queueSemaphore.release();
                }
            }
        }

        @Override
        public boolean add(Runnable object) {
            synchronized (this) {
                return super.add(object);
            }
        }

        @Override
        public boolean remove(Object object) {
            synchronized (this) {
                return super.remove(object);
            }
        }

        @Override
        public Runnable remove() {
            synchronized (this) {
                if (this.isEmpty())
                    return null;
                return super.remove();
            }
        }
    }

    private class ConnectFuture extends SimpleFuture<AsyncNetworkSocket> {
        SocketChannel socket;
        ConnectCallback callback;

        @Override
        protected void cancelCleanup() {
            super.cancelCleanup();
            try {
                if (socket != null)
                    socket.close();
            } catch (Exception e) {
                log("ConnectFuture error ", e);
            }
        }
    }

    public static void setIsLoggable(boolean isLoggable) {
        SocketServer.isLoggable = isLoggable;
    }

    public static void log(String msg) {
        if (SocketServer.isLoggable)
            Log.e(LOGTAG, msg);
    }

    public static void log(String msg, Throwable e) {
        if (SocketServer.isLoggable)
            Log.e(LOGTAG, msg, e);
    }

}
