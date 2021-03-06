/*
 * Copyright 2016 higherfrequencytrading.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.openhft.chronicle.engine.server.internal;

import net.openhft.chronicle.engine.api.pubsub.TopicPublisher;
import net.openhft.chronicle.engine.api.pubsub.TopicSubscriber;
import net.openhft.chronicle.engine.tree.QueueView;
import net.openhft.chronicle.network.connection.WireOutPublisher;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;
import java.util.function.Function;

import static net.openhft.chronicle.engine.server.internal.TopicPublisherHandler.EventId.*;
import static net.openhft.chronicle.engine.server.internal.TopicPublisherHandler.Params.*;
import static net.openhft.chronicle.network.connection.CoreFields.reply;
import static net.openhft.chronicle.network.connection.CoreFields.tid;

/**
 * Created by Rob Austin
 */
public class TopicPublisherHandler<T, M> extends AbstractHandler {

    private final StringBuilder eventName = new StringBuilder();

    private WireOutPublisher publisher;
    private TopicPublisher<T, M> view;
    @Nullable
    private Function<ValueIn, T> wireToT;
    @Nullable
    private Function<ValueIn, M> wireToM;
    private final BiConsumer<WireIn, Long> dataConsumer = new BiConsumer<WireIn, Long>() {

        @Override
        public void accept(@NotNull final WireIn inWire, Long inputTid) {
            assert wireToT != null;
            assert wireToM != null;

            eventName.setLength(0);
            @NotNull final ValueIn valueIn = inWire.readEventName(eventName);
            try {
                assert startEnforceInValueReadCheck(inWire);
                if (registerTopicSubscriber.contentEquals(eventName)) {

                    @NotNull final TopicSubscriber listener = new TopicSubscriber() {

                        @Override
                        public void onMessage(final Object topic, final Object message) {

                            synchronized (publisher) {
                                publisher.put(topic, publish -> {
                                    publish.writeDocument(true, wire -> wire.writeEventName(tid).int64
                                            (inputTid));
                                    publish.writeNotCompleteDocument(false, wire -> wire.writeEventName(reply)
                                            .marshallable(m -> {
                                                m.write(() -> "topic").object(topic);
                                                m.write(() -> "message").object(message);
                                            }));
                                });
                            }
                        }

                        public void onEndOfSubscription() {
                            synchronized (publisher) {
                                publisher.put(null, publish -> {
                                    publish.writeDocument(true, wire -> wire.writeEventName(tid).int64
                                            (inputTid));
                                    publish.writeNotCompleteDocument(false, wire -> wire.writeEventName
                                            (onEndOfSubscription).text(""));

                                });
                            }
                        }

                    };

                    valueIn.marshallable(m -> view.registerTopicSubscriber(listener));
                    return;
                }

                if (publish.contentEquals(eventName)) {

                    valueIn.marshallable(wire -> {
                        @NotNull final Params[] params = publish.params();
                        final T topic = wireToT.apply(wire.read(params[0]));
                        final M message = wireToM.apply(wire.read(params[1]));
                        nullCheck(topic);
                        nullCheck(message);
                        view.publish(topic, message);
                    });
                    return;
                }

                outWire.writeDocument(true, wire -> outWire.writeEventName(tid).int64(inputTid));
                writeData(inWire, out -> {

                    if (getNextAtIndex.contentEquals(eventName)) {
                        out.writeEventName(reply).object(((QueueView) view).getExcerpt(valueIn.int64()));
                        return;
                    }

                    if (getNextAtTopic.contentEquals(eventName)) {
                        out.writeEventName(reply).object(((QueueView) view).getExcerpt(valueIn.object()));
                        return;
                    }

                    if (publishAndIndex.contentEquals(eventName)) {

                        long index = valueIn.applyToMarshallable(wire -> {
                            @NotNull final Params[] params = publish.params();
                            final T topic = wireToT.apply(wire.read(params[0]));
                            final M message = wireToM.apply(wire.read(params[1]));
                            nullCheck(topic);
                            nullCheck(message);
                            return ((QueueView) view).publishAndIndex(topic, message);

                        });
                        out.writeEventName(reply).int64(index);
                        return;
                    }

                });

            } finally {
                assert endEnforceInValueReadCheck(inWire);
            }
        }
    };

    @SuppressWarnings("unchecked")
    void process(@NotNull final WireIn inWire,
                 final WireOutPublisher publisher,
                 final long tid,
                 @NotNull final Wire outWire,
                 final TopicPublisher view,
                 final @NotNull WireAdapter wireAdapter) {

        setOutWire(outWire);

        this.view = view;
        this.publisher = publisher;
        this.wireToT = wireAdapter.wireToKey();
        this.wireToM = wireAdapter.wireToValue();
        dataConsumer.accept(inWire, tid);
    }

    public enum Params implements WireKey {
        topic,
        message,
        index
    }

    public enum EventId implements ParameterizeWireKey {
        publish(topic, message),
        onEndOfSubscription,
        registerTopicSubscriber(topic, message),
        replay,
        getNextAtIndex(index),      // used only by the queue view
        getNextAtTopic(topic),      // used only by the queue view
        next,                // used only by the queue view
        publishAndIndex(topic, message);     // used only by the queue view

        private final WireKey[] params;

        <P extends WireKey> EventId(P... params) {
            this.params = params;
        }

        @NotNull
        public <P extends WireKey> P[] params() {
            return (P[]) this.params;
        }
    }
}
