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

class IdempotentAspectTest {
    IdempotencyStore store;
    Listener target;
    Listener proxy;
    @BeforeEach void setup() {
        store = mock(IdempotencyStore.class);
        when(store.claim(anyString(), anyString(), any())).thenReturn(IdempotencyStore.Claim.ACQUIRED);
        when(store.complete(anyString(), anyString(), any())).thenReturn(true);
        target = new Listener();
        var factory = new AspectJProxyFactory(target);
        factory.addAspect(new IdempotentAspect(store));
        proxy = factory.getProxy();
    }
    @Test void processesAndCompletes() {
        proxy.consume("event-1");
        assertThat(target.calls).isEqualTo(1);
        verify(store).complete(startsWith("rf:"), anyString(), eq(Duration.ofHours(24)));
        verify(store, never()).release(anyString(), anyString());
    }
    @Test void completedDuplicateSkipsBusinessLogic() {
        when(store.claim(anyString(), anyString(), any())).thenReturn(IdempotencyStore.Claim.COMPLETED);
        proxy.consume("event-1");
        assertThat(target.calls).isZero();
        verify(store, never()).complete(anyString(), anyString(), any());
    }
    @Test void busyIsRetriedNotAcknowledged() {
        when(store.claim(anyString(), anyString(), any())).thenReturn(IdempotencyStore.Claim.BUSY);
        assertThatThrownBy(() -> proxy.consume("event-1")).isInstanceOf(RetryableIdempotencyException.class);
        assertThat(target.calls).isZero();
    }
    @Test void failureReleasesOwnerAndPreservesOriginalException() {
        target.failure = new IllegalArgumentException("business failure");
        when(store.release(anyString(), anyString())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> proxy.consume("event-1")).isSameAs(target.failure).hasSuppressedException(new IllegalStateException("offline"));
        verify(store, never()).complete(anyString(), anyString(), any());
    }
    @Test void lostLeaseFailsAfterProcessing() {
        when(store.complete(anyString(), anyString(), any())).thenReturn(false);
        assertThatThrownBy(() -> proxy.consume("event-1")).isInstanceOf(RetryableIdempotencyException.class);
        assertThat(target.calls).isEqualTo(1);
        verify(store, never()).release(anyString(), anyString());
    }
    @Test void unavailableStoreFailsClosed() {
        when(store.claim(anyString(), anyString(), any())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> proxy.consume("event-1")).hasMessage("offline");
        assertThat(target.calls).isZero();
    }
    @Test void invalidKeyCannotClaim() {
        assertThatThrownBy(() -> proxy.consume(" ")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }
    @Test void rejectsAmbientTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> proxy.consume("event-1")).hasMessageContaining("Ambient transactions"); }
        finally { TransactionSynchronizationManager.clear(); }
        verifyNoInteractions(store);
    }
    @Test void rejectsAsyncReturnType() {
        assertThatThrownBy(() -> proxy.invalid("event-1")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }
    public static class Listener {
        int calls;
        RuntimeException failure;
        @Idempotent(key = "#payload", namespace = "fulfillment:orders:v1")
        public void consume(String payload) { calls++; if (failure != null) throw failure; }
        @Idempotent(key = "#payload", namespace = "fulfillment:orders:v1")
        public String invalid(String payload) { return payload; }
    }
}
