package com.samigehi.socket.core;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class AsyncSemaphore {

    private Semaphore semaphore = new Semaphore(0);

    public void acquire() throws InterruptedException {
        ThreadQueue threadQueue = ThreadQueue.getOrCreateThreadQueue(Thread.currentThread());
        AsyncSemaphore last = threadQueue.waiter;
        threadQueue.waiter = this;
        Semaphore queueSemaphore = threadQueue.queueSemaphore;
        try {
            if (semaphore.tryAcquire())
                return;

//            do {
//                while (true) {
//                    Runnable run = threadQueue.remove();
//                    if (run == null)
//                        break;
//                    run.run();
//                }
//
//                int permits = Math.max(1, queueSemaphore.availablePermits());
//                queueSemaphore.acquire(permits);
//            } while (!semaphore.tryAcquire());

            while (true) {
                while (true) {
                    Runnable run = threadQueue.remove();
                    if (run == null)
                        break;
//                        Log.i(LOGTAG, "Pumping for AsyncSemaphore");
                    run.run();
                }

                int permits = Math.max(1, queueSemaphore.availablePermits());
                queueSemaphore.acquire(permits);
                if (semaphore.tryAcquire())
                    break;
            }
        }
        finally {
            threadQueue.waiter = last;
        }
    }

    public boolean tryAcquire(long timeout, TimeUnit timeunit) throws InterruptedException {
        long timeoutMs = TimeUnit.MILLISECONDS.convert(timeout, timeunit);
        ThreadQueue threadQueue = ThreadQueue.getOrCreateThreadQueue(Thread.currentThread());
        AsyncSemaphore last = threadQueue.waiter;
        threadQueue.waiter = this;
        Semaphore queueSemaphore = threadQueue.queueSemaphore;

        try {
            if (semaphore.tryAcquire())
                return true;

            long start = System.currentTimeMillis();
            do {
                // run the queue
                while (true) {
                    Runnable run = threadQueue.remove();
                    if (run == null)
                        break;
//                        Log.i(LOGTAG, "Pumping for AsyncSemaphore");
                    run.run();
                }

                int permits = Math.max(1, queueSemaphore.availablePermits());
                if (!queueSemaphore.tryAcquire(permits, timeoutMs, TimeUnit.MILLISECONDS))
                    return false;
                if (semaphore.tryAcquire())
                    return true;
            }
            while (System.currentTimeMillis() - start < timeoutMs);
            return false;
        }
        finally {
            threadQueue.waiter = last;
        }
    }

    public void release() {
        semaphore.release();
        ThreadQueue.release(this);
    }
}