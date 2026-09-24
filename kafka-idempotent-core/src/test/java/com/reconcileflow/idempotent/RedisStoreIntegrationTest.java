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

@EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION", matches = "true")
class RedisStoreIntegrationTest {
    LettuceConnectionFactory connection;
    RedisIdempotencyStore store;
    String key;
    @BeforeEach void setup() {
        connection = new LettuceConnectionFactory("localhost", Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        connection.afterPropertiesSet(); connection.start();
        store = new RedisIdempotencyStore(new StringRedisTemplate(connection));
        key = "rf:test:" + UUID.randomUUID();
    }
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
    @Test void expiredOwnerCannotDeleteNewClaim() throws Exception {
        store.claim(key,"old",Duration.ofMillis(50));
        Thread.sleep(100);
        assertThat(store.claim(key,"new",Duration.ofSeconds(10))).isEqualTo(IdempotencyStore.Claim.ACQUIRED);
        assertThat(store.release(key,"old")).isFalse();
        assertThat(store.complete(key,"old",Duration.ofSeconds(10))).isFalse();
    }
    @Test void onlyOneConcurrentClaimWins() throws Exception {
        try(var pool = Executors.newFixedThreadPool(8)) {
            var tasks = IntStream.range(0,32).<Callable<IdempotencyStore.Claim>>mapToObj(i -> () -> store.claim(key,"owner-"+i,Duration.ofSeconds(10))).toList();
            long acquired = 0;
            for(var result : pool.invokeAll(tasks)) if(result.get() == IdempotencyStore.Claim.ACQUIRED) acquired++;
            assertThat(acquired).isEqualTo(1);
        }
    }
}
