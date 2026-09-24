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

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Verifies JSON identity through actual AOP advice, including fail-closed malformed inputs.
 * @author Kunal Gandhre
 */
class JsonPayloadTest {
    private final IdempotencyStore store = mock(IdempotencyStore.class);
    private final Listener target = new Listener();

    private Listener proxy() {
        var factory = new AspectJProxyFactory(target);
        factory.addAspect(new IdempotentAspect(store));
        return factory.getProxy();
    }

    /** Equivalent identities deduplicate across field order, whitespace and byte/string delivery. */
    @Test void deduplicatesByIdAndPreservesOriginalPayload() {
        Set<String> completed = new HashSet<>();
        when(store.claim(anyString(), anyString(), any())).thenAnswer(call ->
                completed.contains(call.getArgument(0)) ? IdempotencyStore.Claim.COMPLETED : IdempotencyStore.Claim.ACQUIRED);
        when(store.complete(anyString(), anyString(), any())).thenAnswer(call -> completed.add(call.getArgument(0)));
        Listener proxy = proxy();
        String original = "{\"eventId\":\"order-1\",\"amount\":10}";
        proxy.consume(original);
        assertThat(target.last).isSameAs(original);
        proxy.consume("{ \"amount\": 10, \"eventId\": \"order-1\" }");
        proxy.consume(original.getBytes(StandardCharsets.UTF_8));
        proxy.consume("{\"eventId\":\"order-2\"}");
        assertThat(target.calls).isEqualTo(2);
        assertThat(completed).hasSize(2);
    }

    /** Parsing and ID validation must precede both the store claim and the handler. */
    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "{", "[]", "null", "{}", "{\"eventId\":null}",
            "{\"eventId\":\" \"}", "{\"eventId\":123}", "{\"eventId\":true}",
            "{\"eventId\":{}}", "{\"eventId\":[]}", "{\"eventId\":\"one\"} {}"})
    void rejectsInvalidPayloadBeforeClaim(String payload) {
        assertThatThrownBy(() -> proxy().consume(payload)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(store);
        assertThat(target.calls).isZero();
    }

    /** Nested objects are available through the existing restricted SpEL map indexing. */
    @Test void extractsNestedStringId() {
        when(store.claim(anyString(), anyString(), any())).thenReturn(IdempotencyStore.Claim.COMPLETED);
        proxy().nested("{\"event\":{\"id\":\"nested-1\"}}");
        verify(store).claim(startsWith("rf:"), anyString(), any());
        assertThat(target.calls).isZero();
    }

    /** JSON mode is explicitly for raw data; existing POJO/Map handling uses normal SpEL. */
    @Test void rejectsUnsupportedRawType() {
        assertThatThrownBy(() -> proxy().consume(42)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }

    /** One Object signature exercises both raw String and byte[] deliveries through AOP. */
    public static class Listener {
        int calls;
        Object last;
        @Idempotent(json = true, key = "#payload['eventId']", namespace = "json-tests")
        public void consume(Object payload) { calls++; last = payload; }
        @Idempotent(json = true, key = "#payload['event']['id']", namespace = "json-tests")
        public void nested(String payload) { calls++; }
    }
}
