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

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * Checks Lua state transitions and ownership against a real Redis server.
 * Explicit opt-in prevents an ordinary unit-test run from depending on local infrastructure.
 *
 * @author Kunal Gandhre
 */
@EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION", matches = "true")
class RedisStoreIntegrationTest {
    LettuceConnectionFactory connection;
    RedisIdempotencyStore store;
    String key;
    // Isolate each test with a short-lived unique key; never flush the shared Redis database.
    @BeforeEach void setup() {
        connection = new LettuceConnectionFactory("localhost", Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        connection.afterPropertiesSet(); connection.start();
        store = new RedisIdempotencyStore(new StringRedisTemplate(connection));
        key = "rf:test:" + UUID.randomUUID();
    }
    // Release connection resources even when a test fails.
    /** Verifies new, busy and completed states plus wrong-owner rejection. */
    @AfterEach void close() { connection.destroy(); }
    @Test void stateTransitionsAndOwnership() {
        assertThat(store.claim(key,"a",Duration.ofSeconds(10))).isEqualTo(IdempotencyStore.Claim.ACQUIRED);
        assertThat(store.claim(key,"b",Duration.ofSeconds(10))).isEqualTo(IdempotencyStore.Claim.BUSY);
        assertThat(store.release(key,"b")).isFalse();
        assertThat(store.complete(key,"b",Duration.ofSeconds(10))).isFalse();
        assertThat(store.complete(key,"a",Duration.ofSeconds(10))).isTrue();
        assertThat(store.release(key,"a")).isFalse();
        assertThat(store.claim(key,"c",Duration.ofSeconds(10))).isEqualTo(IdempotencyStore.Claim.COMPLETED);
    }
    /** Lets the lease expire, then proves the previous owner cannot mutate its replacement. */
    @Test void expiredOwnerCannotDeleteNewClaim() throws Exception {
        store.claim(key,"old",Duration.ofMillis(50));
        Thread.sleep(100);
        assertThat(store.claim(key,"new",Duration.ofSeconds(10))).isEqualTo(IdempotencyStore.Claim.ACQUIRED);
        assertThat(store.release(key,"old")).isFalse();
        assertThat(store.complete(key,"old",Duration.ofSeconds(10))).isFalse();
    }
    /** Competing threads must observe one winner while the ten-second lease is live. */
    @Test void onlyOneConcurrentClaimWins() throws Exception {
        try(var pool = Executors.newFixedThreadPool(8)) {
            var tasks = IntStream.range(0,32).<Callable<IdempotencyStore.Claim>>mapToObj(i -> () -> store.claim(key,"owner-"+i,Duration.ofSeconds(10))).toList();
            long acquired = 0;
            for(var result : pool.invokeAll(tasks)) if(result.get() == IdempotencyStore.Claim.ACQUIRED) acquired++;
            assertThat(acquired).isEqualTo(1);
        }
    }
}
