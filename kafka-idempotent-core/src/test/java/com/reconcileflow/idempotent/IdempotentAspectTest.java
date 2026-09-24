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

import org.junit.jupiter.api.*;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Exercises real Spring AOP interception with a mocked store, without Kafka or Redis.
 * Assertions cover both handler execution and state transitions, especially fail-closed behavior.
 *
 * @author Kunal Gandhre
 */
class IdempotentAspectTest {
    IdempotencyStore store;
    Listener target;
    Listener proxy;
    // Use an actual proxy so annotation resolution and advice are tested, not just a helper.
    @BeforeEach void setup() {
        store = mock(IdempotencyStore.class);
        when(store.claim(anyString(), anyString(), any())).thenReturn(IdempotencyStore.Claim.ACQUIRED);
        when(store.complete(anyString(), anyString(), any())).thenReturn(true);
        target = new Listener();
        var factory = new AspectJProxyFactory(target);
        factory.addAspect(new IdempotentAspect(store));
        proxy = factory.getProxy();
    }
    /** A new claim must finish business work before completion is stored. */
    @Test void processesAndCompletes() {
        proxy.consume("event-1");
        assertThat(target.calls).isEqualTo(1);
        verify(store).complete(startsWith("rf:"), anyString(), eq(Duration.ofHours(24)));
        verify(store, never()).release(anyString(), anyString());
    }
    /** A completed duplicate must neither invoke the handler nor refresh completion. */
    @Test void completedDuplicateSkipsBusinessLogic() {
        when(store.claim(anyString(), anyString(), any())).thenReturn(IdempotencyStore.Claim.COMPLETED);
        proxy.consume("event-1");
        assertThat(target.calls).isZero();
        verify(store, never()).complete(anyString(), anyString(), any());
    }
    /** Busy claims throw; returning normally here would let Kafka acknowledge unfinished work. */
    @Test void busyIsRetriedNotAcknowledged() {
        when(store.claim(anyString(), anyString(), any())).thenReturn(IdempotencyStore.Claim.BUSY);
        assertThatThrownBy(() -> proxy.consume("event-1")).isInstanceOf(RetryableIdempotencyException.class);
        assertThat(target.calls).isZero();
    }
    /** Cleanup failure must not replace the business error that drives retry decisions. */
    @Test void failureReleasesOwnerAndPreservesOriginalException() {
        target.failure = new IllegalArgumentException("business failure");
        when(store.release(anyString(), anyString())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> proxy.consume("event-1")).isSameAs(target.failure).hasSuppressedException(new IllegalStateException("offline"));
        verify(store, never()).complete(anyString(), anyString(), any());
    }
    /** Completion failure after work is uncertain; do not release any replacement owner. */
    @Test void lostLeaseFailsAfterProcessing() {
        when(store.complete(anyString(), anyString(), any())).thenReturn(false);
        assertThatThrownBy(() -> proxy.consume("event-1")).isInstanceOf(RetryableIdempotencyException.class);
        assertThat(target.calls).isEqualTo(1);
        verify(store, never()).release(anyString(), anyString());
    }
    /** If claiming is unavailable, business code must never run. */
    @Test void unavailableStoreFailsClosed() {
        when(store.claim(anyString(), anyString(), any())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> proxy.consume("event-1")).hasMessage("offline");
        assertThat(target.calls).isZero();
    }
    /** Reject blank identifiers before any Redis interaction. */
    @Test void invalidKeyCannotClaim() {
        assertThatThrownBy(() -> proxy.consume(" ")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }
    /** Restore thread-local transaction state so this test cannot contaminate later tests. */
    @Test void rejectsAmbientTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> proxy.consume("event-1")).hasMessageContaining("Ambient transactions"); }
        finally { TransactionSynchronizationManager.clear(); }
        verifyNoInteractions(store);
    }
    /** Reject non-void signatures before claiming; this does not detect every async mechanism. */
    @Test void rejectsAsyncReturnType() {
        assertThatThrownBy(() -> proxy.invalid("event-1")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }
    /** Minimal proxy target with observable calls and a configurable business failure. */
    public static class Listener {
        int calls;
        RuntimeException failure;
        @Idempotent(key = "#payload", namespace = "fulfillment:orders:v1")
        public void consume(String payload) { calls++; if (failure != null) throw failure; }
        @Idempotent(key = "#payload", namespace = "fulfillment:orders:v1")
        public String invalid(String payload) { return payload; }
    }
}
