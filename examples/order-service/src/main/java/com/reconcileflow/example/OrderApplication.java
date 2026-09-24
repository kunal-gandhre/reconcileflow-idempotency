/*
 * Copyright 2026 ReconcileFlow
 * Author: Kunal Gandhre
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reconcileflow.example;

import com.reconcileflow.idempotent.Idempotent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Runnable local example connecting Kafka delivery to the starter and Redis state machine.
 * The payload is an event ID; successful work is represented by a console line.
 * It demonstrates deduplication, not exactly-once external business operations.
 *
 * @author Kunal Gandhre
 */
@SpringBootApplication
public class OrderApplication {
    /** Starts Spring, auto-configures the store/aspect, and starts the Kafka listener container. */
    public static void main(String[] args) { SpringApplication.run(OrderApplication.class, args); }
    /** Runs only on a newly acquired claim; repeated completed event IDs skip this method. */
    @KafkaListener(topics = "order-events", groupId = "fulfillment-demo")
    @Idempotent(key = "#payload", namespace = "fulfillment-demo:order-events:v1")
    public void process(String eventId) {
        System.out.println("Processed order event: " + eventId);
    }
    /** Demo retries indefinitely instead of silently recovering an unprocessed record. */
    @Bean DefaultErrorHandler errorHandler() {
        var handler = new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        // This teaching example retries runtime failures indefinitely; production needs an explicit recovery policy.
        handler.addRetryableExceptions(RuntimeException.class);
        return handler;
    }
}
