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

package com.reconcileflow.idempotent;

import java.lang.annotation.*;

/**
 * Declares the event identity and retention policy for a synchronous Spring bean method.
 * Use alongside {@code @KafkaListener}; this annotation does not register a Kafka listener.
 * Methods must return void and finish all business work before returning.
 *
 * @author Kunal Gandhre
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    /** Read-only SpEL expression; #payload is the first argument and #pN selects argument N. */
    String key();
    /** Stable logical consumer + topic scope, e.g. fulfillment:orders:v1. */
    String namespace();
    /** Maximum claim lifetime, not a handler timeout; expiry can permit concurrent work. */
    String lease() default "60s";
    /** How long completed work is remembered, measured from successful completion. */
    String retention() default "24h";
}
