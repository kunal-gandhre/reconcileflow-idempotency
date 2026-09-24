package com.reconcileflow.example;

import com.reconcileflow.idempotent.Idempotent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@SpringBootApplication
public class OrderApplication {
    public static void main(String[] args) { SpringApplication.run(OrderApplication.class, args); }
    @KafkaListener(topics = "order-events", groupId = "fulfillment-demo")
    @Idempotent(key = "#payload", namespace = "fulfillment-demo:order-events:v1")
    public void process(String eventId) {
        System.out.println("Processed order event: " + eventId);
    }
    /** Demo retries indefinitely instead of silently recovering an unprocessed record. */
    @Bean DefaultErrorHandler errorHandler() {
        var handler = new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addRetryableExceptions(RuntimeException.class);
        return handler;
    }
}
