/**
 * *************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 * *
 * http://www.apache.org/licenses/LICENSE-2.0                   *
 * *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ***************************************************************/

package org.apache.james.task.eventsourcing.distributed;

import java.nio.charset.StandardCharsets;

import javax.annotation.PreDestroy;

import org.apache.commons.lang3.NotImplementedException;
import org.apache.james.backend.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backend.rabbitmq.SimpleConnectionPool;
import org.apache.james.lifecycle.api.Startable;
import org.apache.james.server.task.json.JsonTaskSerializer;
import org.apache.james.task.Task;
import org.apache.james.task.TaskId;
import org.apache.james.task.TaskManagerWorker;
import org.apache.james.task.TaskWithId;
import org.apache.james.task.WorkQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableMap;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Connection;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.ReceiverOptions;
import reactor.rabbitmq.Sender;

public class RabbitMQWorkQueue implements WorkQueue, Startable {
    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMQWorkQueue.class);

    static final Integer MAX_CHANNELS_NUMBER = 1;
    static final String EXCHANGE_NAME = "taskManagerWorkQueueExchange";
    static final String QUEUE_NAME = "taskManagerWorkQueue";
    static final String ROUTING_KEY = "taskManagerWorkQueueRoutingKey";
    public static final String TASK_ID = "taskId";

    private final TaskManagerWorker worker;
    private final Mono<Connection> connectionMono;
    private final ReactorRabbitMQChannelPool channelPool;
    private final JsonTaskSerializer taskSerializer;
    private Sender sender;
    private RabbitMQExclusiveConsumer receiver;

    public RabbitMQWorkQueue(TaskManagerWorker worker, SimpleConnectionPool simpleConnectionPool, JsonTaskSerializer taskSerializer) {
        this.worker = worker;
        this.connectionMono = simpleConnectionPool.getResilientConnection();
        this.taskSerializer = taskSerializer;
        this.channelPool = new ReactorRabbitMQChannelPool(connectionMono, MAX_CHANNELS_NUMBER);
    }

    public void start() {
        sender = channelPool.createSender();

        receiver = new RabbitMQExclusiveConsumer(new ReceiverOptions().connectionMono(connectionMono));

        sender.declareExchange(ExchangeSpecification.exchange(EXCHANGE_NAME)).block();
        sender.declare(QueueSpecification.queue(QUEUE_NAME).durable(true)).block();
        sender.bind(BindingSpecification.binding(EXCHANGE_NAME, ROUTING_KEY, QUEUE_NAME)).block();

        consumeWorkqueue();
    }

    private void consumeWorkqueue() {
        receiver.consumeExclusiveManualAck(QUEUE_NAME, new ConsumeOptions())
            .subscribeOn(Schedulers.elastic())
            .flatMap(this::executeTask)
            .subscribe();
    }

    private Mono<Task.Result> executeTask(AcknowledgableDelivery delivery) {
        String json = new String(delivery.getBody(), StandardCharsets.UTF_8);

        TaskId taskId = TaskId.fromString(delivery.getProperties().getHeaders().get(TASK_ID).toString());

        try {
            Task task = taskSerializer.deserialize(json);
            delivery.ack();
            return worker.executeTask(new TaskWithId(taskId, task));
        } catch (Exception e) {
            LOGGER.error("Unable to run submitted Task " + taskId.asString(), e);
            delivery.ack();
            worker.fail(taskId, e);
            return Mono.empty();
        }
    }

    @Override
    public void submit(TaskWithId taskWithId) {
        try {
            byte[] payload = taskSerializer.serialize(taskWithId.getTask()).getBytes(StandardCharsets.UTF_8);
            AMQP.BasicProperties basicProperties = new AMQP.BasicProperties.Builder()
                .headers(ImmutableMap.of(TASK_ID, taskWithId.getId().asString()))
                .build();
            OutboundMessage outboundMessage = new OutboundMessage(EXCHANGE_NAME, ROUTING_KEY, basicProperties, payload);
            sender.send(Mono.just(outboundMessage)).block();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void cancel(TaskId taskId) {
         throw new NotImplementedException("Cancel not done yet");
    }

    @Override
    @PreDestroy
    public void close() {
        channelPool.close();
    }
}
