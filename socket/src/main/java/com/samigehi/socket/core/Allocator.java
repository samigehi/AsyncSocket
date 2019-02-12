package com.samigehi.socket.core;


import java.nio.ByteBuffer;

public class Allocator {
    private final int maxAlloc;
    private int currentAlloc = 0;
    private int minAlloc = 2 << 11;

    public Allocator(int maxAlloc) {
        this.maxAlloc = maxAlloc;
    }

    public Allocator() {
        maxAlloc = ByteBufferReader.MAX_ITEM_SIZE;
    }

    public ByteBuffer allocate() {
        return allocate(currentAlloc);
    }

    public ByteBuffer allocate(int currentAlloc) {
        return ByteBufferReader.obtain(Math.min(Math.max(currentAlloc, minAlloc), maxAlloc));
    }

    public void track(long read) {
        currentAlloc = (int)read * 2;
    }

    public int getMaxAlloc() {
        return maxAlloc;
    }

    public void setCurrentAlloc(int currentAlloc) {
        this.currentAlloc = currentAlloc;
    }

    public int getMinAlloc() {
        return minAlloc;
    }

    public Allocator setMinAlloc(int minAlloc ) {
        this.minAlloc = minAlloc;
        return this;
    }
}

