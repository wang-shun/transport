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
package com.aitusoftware.transport.messaging;

import com.aitusoftware.transport.Fixtures;
import com.aitusoftware.transport.buffer.PageCache;
import com.aitusoftware.transport.messaging.proxy.PublisherFactory;
import com.aitusoftware.transport.messaging.proxy.Subscriber;
import com.aitusoftware.transport.messaging.proxy.SubscriberFactory;
import com.aitusoftware.transport.reader.StreamingReader;
import com.aitusoftware.transport.threads.Idlers;
import org.agrona.collections.Int2ObjectHashMap;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;


public final class TopicDispatcherRecordHandlerTest
{
    private final Path tempDir = Fixtures.tempDirectory();
    private final SubscriberFactory subscriberFactory = new SubscriberFactory();
    private PublisherFactory factory;
    private PageCache pageCache;

    @Before
    public void setUp() throws Exception
    {
        pageCache = PageCache.create(tempDir, 4096);
        factory = new PublisherFactory(pageCache);
    }

    @Test
    public void shouldDispatchMessages() throws Exception
    {
        final TestTopicMessageCounter testTopicMessageCounter = new TestTopicMessageCounter();
        final Subscriber testTopicSubscriber =
                subscriberFactory.getSubscriber(TestTopic.class, testTopicMessageCounter);
        final Subscriber otherTopicSubscriber = subscriberFactory.getSubscriber(OtherTopic.class, new OtherTopicMessageCounter());

        final TestTopic proxy = factory.getPublisherProxy(TestTopic.class);
        final OtherTopic paramTester = factory.getPublisherProxy(OtherTopic.class);

        proxy.say("hola", 7);

        paramTester.testParams(true, (byte) 5, (short) 7, 11,
                13.7f, 17L, 19.37d, "first", "second");

        proxy.say("bonjour", 11);

        paramTester.testParams(false, (byte) -5, (short) -7, -11,
                Float.MAX_VALUE, Long.MIN_VALUE, Double.POSITIVE_INFINITY, "first", "second");

        final Int2ObjectHashMap<Subscriber> subscriberMap =
                new Int2ObjectHashMap<>();

        subscriberMap.put(testTopicSubscriber.getTopicId(), testTopicSubscriber);
        subscriberMap.put(otherTopicSubscriber.getTopicId(), otherTopicSubscriber);

        final TopicDispatcherRecordHandler topicDispatcher =
                new TopicDispatcherRecordHandler(subscriberMap);

        new StreamingReader(pageCache, topicDispatcher, false, Idlers.staticPause(1, TimeUnit.MILLISECONDS)).process();

        assertThat(testTopicMessageCounter.getMessageCount(), is(2));
    }

}