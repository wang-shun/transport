package com.aitusoftware.transport.buffer;

import com.aitusoftware.transport.files.Directories;
import com.aitusoftware.transport.files.Filenames;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PageCache
{
    private static final VarHandle CURRENT_PAGE_VH;
    private static final VarHandle CURRENT_PAGE_NUMBER_VH;
    private static final int INITIAL_PAGE_NUMBER = 0;

    static
    {
        try
        {
            CURRENT_PAGE_VH = MethodHandles.lookup().
                    findVarHandle(PageCache.class, "currentPage", Page.class);
            CURRENT_PAGE_NUMBER_VH = MethodHandles.lookup().
                    findVarHandle(PageCache.class, "currentPageNumber", int.class);
        }
        catch (NoSuchFieldException | IllegalAccessException e)
        {
            throw new IllegalStateException("Unable to obtain VarHandle");
        }

    }

    private final PageAllocator allocator;
    private final Path path;
    private volatile Page currentPage;
    private volatile int currentPageNumber;

    private PageCache(final int pageSize, final Path path)
    {
        // TODO should handle initialisation from existing file-system resources
        this.path = path;
        allocator = new PageAllocator(this.path, pageSize);
        CURRENT_PAGE_VH.setRelease(this, allocator.safelyAllocatePage(INITIAL_PAGE_NUMBER));
        CURRENT_PAGE_NUMBER_VH.setRelease(this, INITIAL_PAGE_NUMBER);
    }

    // contain page-cache header
    void append(final ByteBuffer source)
    {
        final Page page = (Page) CURRENT_PAGE_VH.getVolatile(this);
        try
        {
            final WriteResult writeResult = page.write(source);
            switch (writeResult)
            {
                case SUCCESS:
                    return;
                case FAILURE:
                    throw new RuntimeException(String.format(
                            "Failed to append to current page: %s", currentPage));
                case MESSAGE_TOO_LARGE:
                    throw new RuntimeException(String.format(
                            "Message too large for current page: %s", currentPage));
                case NOT_ENOUGH_SPACE:
                    handleOverflow(source, page);
                    break;
                default:
                    throw new UnsupportedOperationException(writeResult.name());
            }
        }
        catch (RuntimeException e)
        {
            throw new RuntimeException(String.format(
                    "Failed to write to current page: %s", currentPage
            ), e);
        }
    }

    long estimateTotalLength()
    {
        final Page page = (Page) CURRENT_PAGE_VH.get(this);
        return ((long) page.getPageNumber()) * page.totalDataSize() +
                page.nextAvailablePosition();
    }

    public boolean isPageAvailable(final int pageNumber)
    {
        // optimisation - cache file names
        return Files.exists(Filenames.forPageNumber(pageNumber, path));
    }

    public Page getPage(final int pageNumber)
    {
        // optimisation - cache pages
        return allocator.loadExisting(pageNumber);
    }

    private void handleOverflow(final ByteBuffer message, final Page page)
    {
        final int pageNumber = page.getPageNumber();
        while (!Thread.currentThread().isInterrupted())
        {
            if (((int) CURRENT_PAGE_NUMBER_VH.get(this)) == pageNumber + 1 &&
                    ((Page) CURRENT_PAGE_VH.get(this)).getPageNumber() != pageNumber + 1)
            {
                // another write has won, and will allocate a new page
                while ((((Page) CURRENT_PAGE_VH.get(this)).getPageNumber() != pageNumber + 1))
                {
                    Thread.yield();
                }
                break;
            }
            if (CURRENT_PAGE_NUMBER_VH.compareAndSet(this, pageNumber, pageNumber + 1))
            {
                // this thread won, allocate a new page
                CURRENT_PAGE_VH.setRelease(this, allocator.safelyAllocatePage(pageNumber + 1));
                break;
            }
        }

        append(message);
    }

    public static PageCache create(final Path path, final int pageSize)
    {
        Directories.ensureDirectoryExists(path);

        return new PageCache(pageSize, path);
    }
}