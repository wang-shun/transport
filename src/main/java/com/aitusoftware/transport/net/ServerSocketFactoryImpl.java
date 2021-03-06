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
package com.aitusoftware.transport.net;

import org.agrona.collections.Int2ObjectHashMap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerSocketFactoryImpl implements ServerSocketFactory
{
    private final Int2ObjectHashMap<SocketAddress> topicToListenerAddress =
            new Int2ObjectHashMap<>();
    private final Map<SocketAddress, ServerSocketChannel> createdSockets = new ConcurrentHashMap<>();

    @Override
    public void registerTopicAddress(final int topicId, final SocketAddress socketAddress)
    {
        topicToListenerAddress.put(topicId, socketAddress);
    }

    @Override
    public ServerSocketChannel acquire(final int topicId)
    {
        return createdSockets.computeIfAbsent(topicToListenerAddress.get(topicId), addr -> {
            if (addr == null)
            {
                return null;
            }
            try
            {
                final ServerSocketChannel serverChannel = ServerSocketChannel.open();
                serverChannel.bind(addr);
                serverChannel.configureBlocking(false);
                return serverChannel;
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
        });
    }
}
