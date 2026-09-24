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

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.security.MessageDigest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Wraps an annotated invocation with validation, key extraction, claiming and completion.
 * Runs outside normal transaction advice so synchronous business work can commit first.
 * Redis and external side effects are not atomic: lease loss and crashes can allow repeats.
 *
 * @author Kunal Gandhre
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class IdempotentAspect {
    private final IdempotencyStore store;
    private final SpelExpressionParser parser = new SpelExpressionParser();
    /** Receives the configured store; store errors intentionally propagate to the consumer. */
    public IdempotentAspect(IdempotencyStore store) { this.store = store; }

    /** Executes business work only for a new claim; completed duplicates return normally. */
    @Around("@annotation(config)")
    public Object invoke(ProceedingJoinPoint call, Idempotent config) throws Throwable {
        // Resolve the concrete method behind a Spring proxy before checking its contract.
        Method method = AopUtils.getMostSpecificMethod(((MethodSignature) call.getSignature()).getMethod(), call.getTarget().getClass());
        if (method.getReturnType() != void.class || call.getArgs().length == 0)
            throw new IllegalArgumentException("@Idempotent requires a synchronous void method with a payload argument");
        // An outer transaction could commit after Redis says DONE, so reject that ordering.
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Ambient transactions are unsupported; commit business work inside the handler");
        if (config.namespace().isBlank()) throw new IllegalArgumentException("Namespace is required");
        Duration lease = duration(config.lease());
        Duration retention = duration(config.retention());
        // Expose arguments as read-only data; do not enable arbitrary SpEL method execution.
        var context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        context.setVariable("payload", config.json() ? JsonPayload.read(call.getArgs()[0]) : call.getArgs()[0]);
        for (int i = 0; i < call.getArgs().length; i++) context.setVariable("p" + i, call.getArgs()[i]);
        Object raw = parser.parseExpression(config.key()).getValue(context);
        // JSON identities must be strings, avoiding numeric precision/coercion differences across clients.
        if (config.json() && !(raw instanceof String))
            throw new IllegalArgumentException("JSON idempotency key must be a nonempty string");
        if (!(raw instanceof String || raw instanceof Number || raw instanceof UUID) || raw.toString().isBlank())
            throw new IllegalArgumentException("Idempotency key must be a nonempty string, number or UUID");
        // Length-prefix the UTF-8 namespace to avoid delimiter collisions and match Go keys.
        // Hashing keeps raw event IDs out of Redis key names; it is not anonymization.
        String input = config.namespace().getBytes(StandardCharsets.UTF_8).length + ":" + config.namespace() + ":" + raw;
        String key = "rf:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        // A fresh token fences Redis mutations by expired workers, not external side effects.
        String owner = UUID.randomUUID().toString();
        // Only COMPLETED is safe to skip. BUSY must fail so the consumer can retry later.
        var claim = store.claim(key, owner, lease);
        if (claim == IdempotencyStore.Claim.COMPLETED) return null;
        if (claim != IdempotencyStore.Claim.ACQUIRED) throw new RetryableIdempotencyException("Event is still processing; retry later");
        try {
            // Return only after synchronous business work (including its own transaction) finishes.
            call.proceed();
        } catch (Throwable failure) {
            // Owner checking prevents an expired handler from deleting a newer worker's claim.
            try { store.release(key, owner); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        // Do not release an uncertain completion: business work has already succeeded.
        if (!store.complete(key, owner, retention))
            throw new RetryableIdempotencyException("Processing lease lost; business outcome requires reconciliation");
        return null;
    }
    /** Parses human-readable durations and rejects values Redis cannot store as positive milliseconds. */
    private static Duration duration(String value) {
        Duration result = DurationStyle.detectAndParse(value);
        if (result.toMillis() <= 0) throw new IllegalArgumentException("Duration must be at least 1ms");
        return result;
    }
}
