package com.aitusoftware.transport.reader;

import com.aitusoftware.transport.buffer.Offsets;
import com.aitusoftware.transport.buffer.Page;
import com.aitusoftware.transport.buffer.PageCache;

import java.nio.ByteBuffer;

public final class StreamingReader
{
    private final PageCache pageCache;
    private final RecordHandler recordHandler;
    private final boolean tail;
    private final boolean zeroCopy;
    private int pageNumber = 0;
    private int position = 0;
    private Page page;
    private ByteBuffer buffer = ByteBuffer.allocateDirect(256);
    private ByteBuffer slice;

    public StreamingReader(
            final PageCache pageCache, final RecordHandler recordHandler,
            final boolean tail, final boolean zeroCopy)
    {
        this.pageCache = pageCache;
        this.recordHandler = recordHandler;
        this.tail = tail;
        this.zeroCopy = zeroCopy;
    }

    public void process()
    {
        while (!Thread.currentThread().isInterrupted())
        {
            if (!processRecord())
            {
                if (!tail)
                {
                    return;
                }
                Thread.yield();
            }
        }
    }

    public boolean processRecord()
    {
        if (page == null)
        {
            if (!pageCache.isPageAvailable(pageNumber))
            {
                return false;
            }
            page = pageCache.getPage(pageNumber);
            slice = page.slice();
        }

        final int header = page.header(position);
        if (Page.isReady(header))
        {
            final int recordLength = Page.recordLength(header);
            if (zeroCopy)
            {
                page.setSlice(slice, position, recordLength);
                recordHandler.onRecord(slice, pageNumber, position);
            }
            else
            {
                if (recordLength > buffer.capacity())
                {
                    buffer = ByteBuffer.allocateDirect(toNextPowerOfTwo(recordLength));
                }
                buffer.clear();
                buffer.limit(recordLength);
                page.read(position, buffer);
                recordHandler.onRecord(buffer, pageNumber, position);
            }
            position += recordLength;
            position = Offsets.getAlignedPosition(position);
            return true;
        }
        page = null;
        pageNumber++;
        position = 0;
        return true;
    }

    private static int toNextPowerOfTwo(final int input)
    {
        return Integer.highestOneBit(input) * 2;
    }
}