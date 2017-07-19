/*
 * Copyright 2017 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.storm.kafka.spout;

import static org.apache.storm.kafka.spout.builders.SingleTopicKafkaSpoutConfiguration.getKafkaSpoutConfigBuilder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.storm.kafka.spout.builders.SingleTopicKafkaSpoutConfiguration;
import org.apache.storm.kafka.spout.internal.KafkaConsumerFactory;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.utils.Time;
import org.apache.storm.utils.Time.SimulatedTime;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.MockitoAnnotations;

public class KafkaSpoutCommitTest {

    private final long offsetCommitPeriodMs = 2_000;
    private final TopologyContext contextMock = mock(TopologyContext.class);
    private final SpoutOutputCollector collectorMock = mock(SpoutOutputCollector.class);
    private final Map<String, Object> conf = new HashMap<>();
    private final TopicPartition partition = new TopicPartition(SingleTopicKafkaSpoutConfiguration.TOPIC, 1);
    private KafkaConsumer<String, String> consumerMock;
    private KafkaSpout<String, String> spout;
    private KafkaSpoutConfig spoutConfig;

    @Captor
    private ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> commitCapture;

    private void setupSpout(Set<TopicPartition> assignedPartitions) {
        MockitoAnnotations.initMocks(this);
        spoutConfig = getKafkaSpoutConfigBuilder(-1)
                .setOffsetCommitPeriodMs(offsetCommitPeriodMs)
                .build();

        consumerMock = mock(KafkaConsumer.class);
        KafkaConsumerFactory<String, String> consumerFactory = new KafkaConsumerFactory<String, String>() {
            @Override
            public KafkaConsumer<String, String> createConsumer(KafkaSpoutConfig<String, String> kafkaSpoutConfig) {
                return consumerMock;
            }
        };

        //Set up a spout listening to 1 topic partition
        spout = new KafkaSpout<>(spoutConfig, consumerFactory);

        spout.open(conf, contextMock, collectorMock);
        spout.activate();

        ArgumentCaptor<ConsumerRebalanceListener> rebalanceListenerCapture = ArgumentCaptor.forClass(ConsumerRebalanceListener.class);
        verify(consumerMock).subscribe(anyCollection(), rebalanceListenerCapture.capture());

        //Assign partitions to the spout
        ConsumerRebalanceListener consumerRebalanceListener = rebalanceListenerCapture.getValue();
        consumerRebalanceListener.onPartitionsAssigned(assignedPartitions);
    }

    @Test
    public void testCommitSuccessWithOffsetVoids() {
        //Verify that the commit logic can handle offset voids
        try (SimulatedTime simulatedTime = new SimulatedTime()) {
            setupSpout(Collections.singleton(partition));
            Map<TopicPartition, List<ConsumerRecord<String, String>>> records = new HashMap<>();
            List<ConsumerRecord<String, String>> recordsForPartition = new ArrayList<>();
            // Offsets emitted are 0,1,2,3,4,<void>,8,9
            for (int i = 0; i < 5; i++) {
                recordsForPartition.add(new ConsumerRecord(partition.topic(), partition.partition(), i, "key", "value"));
            }
            for (int i = 8; i < 10; i++) {
                recordsForPartition.add(new ConsumerRecord(partition.topic(), partition.partition(), i, "key", "value"));
            }
            records.put(partition, recordsForPartition);

            when(consumerMock.poll(anyLong()))
                    .thenReturn(new ConsumerRecords(records));

            for (int i = 0; i < recordsForPartition.size(); i++) {
                spout.nextTuple();
            }

            ArgumentCaptor<KafkaSpoutMessageId> messageIds = ArgumentCaptor.forClass(KafkaSpoutMessageId.class);
            verify(collectorMock, times(recordsForPartition.size())).emit(anyString(), anyList(), messageIds.capture());

            for (KafkaSpoutMessageId messageId : messageIds.getAllValues()) {
                spout.ack(messageId);
            }

            // Advance time and then trigger first call to kafka consumer commit; the commit will progress till offset 4
            Time.advanceTime(KafkaSpout.TIMER_DELAY_MS + offsetCommitPeriodMs);
            Map<TopicPartition, List<ConsumerRecord<String, String>>> emptyConsumerRecords = Collections.emptyMap();
            when(consumerMock.poll(anyLong()))
                    .thenReturn(new ConsumerRecords<>(emptyConsumerRecords));
            spout.nextTuple();

            InOrder inOrder = inOrder(consumerMock);
            inOrder.verify(consumerMock).commitSync(commitCapture.capture());
            inOrder.verify(consumerMock).poll(anyLong());

            //verify that Offset 4 was last committed offset
            //the offset void should be bridged in the next commit
            Map<TopicPartition, OffsetAndMetadata> commits = commitCapture.getValue();
            assertTrue(commits.containsKey(partition));
            assertEquals(4, commits.get(partition).offset());

            //Trigger second kafka consumer commit
            reset(consumerMock);
            when(consumerMock.poll(anyLong()))
                    .thenReturn(new ConsumerRecords<String, String>(emptyConsumerRecords));
            Time.advanceTime(KafkaSpout.TIMER_DELAY_MS + offsetCommitPeriodMs);
            spout.nextTuple();

            inOrder = inOrder(consumerMock);
            inOrder.verify(consumerMock).commitSync(commitCapture.capture());
            inOrder.verify(consumerMock).poll(anyLong());

            //verify that Offset 9 was last committed offset
            commits = commitCapture.getValue();
            assertTrue(commits.containsKey(partition));
            assertEquals(9, commits.get(partition).offset());
        }
    }

}