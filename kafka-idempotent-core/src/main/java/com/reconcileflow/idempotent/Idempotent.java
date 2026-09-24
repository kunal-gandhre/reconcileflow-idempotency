package com.reconcileflow.idempotent;

import java.lang.annotation.*;

/** Apply alongside @KafkaListener to a synchronous, single-record, void method. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    String key();
    /** Stable logical consumer + topic scope, e.g. fulfillment:orders:v1. */
    String namespace();
    String lease() default "60s";
    String retention() default "24h";
}
