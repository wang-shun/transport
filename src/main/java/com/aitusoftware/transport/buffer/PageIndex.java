/*
 * Copyright 2017 - 2018 Aitu Software Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aitusoftware.transport.buffer;

import com.aitusoftware.transport.files.Buffers;
import com.aitusoftware.transport.files.Filenames;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;

final class PageIndex
{
    static final int SLOTS = 128;
    private static final int SLOT_MASK = SLOTS - 1;
    private static final int SLOT_SIZE = 4;
    private static final int FILE_SIZE = SLOTS * SLOT_SIZE;

    private final Slab slab;
    private final Path path;

    static PageIndex forPageCache(final Path path) throws IOException
    {
        final Path indexFile = path.resolve("pages.idx");
        try
        {
            try (final FileChannel channel = FileChannel.open(indexFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 final RandomAccessFile file = new RandomAccessFile(indexFile.toFile(), "rw"))
            {
                file.setLength(FILE_SIZE);
            }
        }
        catch (IOException e)
        {
            // already created
        }

        final PageIndex pageIndex = new PageIndex(SlabFactory.createSlab(Buffers.map(
                        indexFile, FILE_SIZE)), path);
        pageIndex.refresh();
        return pageIndex;
    }

    private PageIndex(final Slab slab, final Path path)
    {
        this.slab = slab;
        this.path = path;
    }

    void refresh()
    {
        final String[] files = path.toFile().
                list((dir, name) -> name.endsWith(Filenames.SUFFIX));
        if (files != null)
        {
            Arrays.sort(files, Comparator.comparingInt(Filenames::toPageNumber));
            for (String file : files)
            {
                onPageCreated(Filenames.toPageNumber(file));
            }
        }
    }

    void onPageCreated(final int pageNumber)
    {
        final int offset = toOffset(pageNumber);
        final int pageAtSlot = slab.getIntVolatile(offset);
        if (pageNumber > pageAtSlot)
        {
            slab.compareAndSetInt(offset, pageAtSlot, pageNumber);
        }
    }

    boolean isPageCreated(final int pageNumber)
    {
        final int offset = toOffset(pageNumber);
        return slab.getIntVolatile(offset) == pageNumber;
    }

    boolean isLessThanLowestTrackedPageNumber(final int pageNumber)
    {
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < SLOTS; i++)
        {
            min = Math.min(min, slab.getIntVolatile(toOffset(i)));
        }
        return pageNumber < min;
    }

    int getHighestPageNumber()
    {
        int max = -1;
        for (int i = 0; i < SLOTS; i++)
        {
            max = Math.max(max, slab.getIntVolatile(toOffset(i)));
        }
        return max;
    }

    private static int toOffset(final int pageNumber)
    {
        return 4 * (pageNumber & SLOT_MASK);
    }
}