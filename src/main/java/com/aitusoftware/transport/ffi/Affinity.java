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
package com.aitusoftware.transport.ffi;

import com.aitusoftware.transport.threads.SingleThreaded;
import jnr.ffi.LastError;
import jnr.ffi.LibraryLoader;
import jnr.ffi.Pointer;
import jnr.ffi.Runtime;
import jnr.ffi.types.pid_t;
import jnr.ffi.types.size_t;

import java.nio.ByteBuffer;
import java.util.Arrays;

@SingleThreaded
public final class Affinity
{
    private static final int MAX_CPUS = Integer.getInteger("transport.affinity.maxCpuCount", 256);
    private static final int BITS_PER_BYTE = 8;
    private static final int BYTE_COUNT = MAX_CPUS / BITS_PER_BYTE;
    private final byte[] cpuMask = new byte[BYTE_COUNT];
    private final Pointer mask = Pointer.wrap(Runtime.getSystemRuntime(), ByteBuffer.wrap(cpuMask));
    private final LibC libc = LibraryLoader.create(LibC.class).load("c");

    @SuppressWarnings("SpellCheckingInspection")
    public interface LibC
    {
        int sched_setaffinity(@pid_t int pid, @size_t int cpusetsize, Pointer mask);
        int sched_getaffinity(@pid_t int pid, @size_t int cpusetsize, Pointer mask);
    }

    public void setCurrentThreadCpuAffinityAndValidate(final int cpu)
    {
        setCurrentThreadCpuAffinity(cpu);
        if (cpu != getCurrentThreadCpuAffinity())
        {
            throw new IllegalStateException("Unable to set thread affinity");
        }
    }

    public void setCurrentThreadCpuAffinity(final int cpu)
    {
        Arrays.fill(cpuMask, (byte) 0);
        final int byteIndex = cpu / BITS_PER_BYTE;
        final int bitIndex = cpu - (BITS_PER_BYTE * byteIndex);
        cpuMask[byteIndex] = (byte) (1 << bitIndex);

        final int returnValue = libc.sched_setaffinity(0, BYTE_COUNT, mask);

        if (returnValue != 0)
        {
            throw new IllegalStateException(String.format(
                    "Failed to set affinity, response code: %d, error code: %d",
                    returnValue, LastError.getLastError(Runtime.getSystemRuntime())));
        }
    }

    public int getCurrentThreadCpuAffinity()
    {
        Arrays.fill(cpuMask, (byte) 0);
        final int returnValue = libc.sched_getaffinity(0, BYTE_COUNT, mask);

        if (returnValue != 0)
        {
            throw new IllegalStateException(String.format(
                    "Failed to get affinity, response code: %d, error code: %d",
                    returnValue, LastError.getLastError(Runtime.getSystemRuntime())));
        }

        int cpuAffinity = -1;
        for (int i = 0; i < BYTE_COUNT; i++)
        {
            if (cpuMask[i] != 0)
            {
                if (cpuAffinity != -1)
                {
                    throw new IllegalStateException("Thread affinity not set");
                }
                cpuAffinity = Integer.numberOfTrailingZeros(cpuMask[i]);
            }
        }
        return cpuAffinity;
    }
}