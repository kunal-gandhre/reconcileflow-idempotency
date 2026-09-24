package com.reconcileflow.idempotent;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Single Redis key, atomic transitions, token-checked completion and release. */
public final class RedisIdempotencyStore implements IdempotencyStore {
    private static final DefaultRedisScript<Long> CLAIM = new DefaultRedisScript<>("""
        local value = redis.call('GET', KEYS[1])
        if not value then
          redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
          return 1
        end
        if value == 'DONE' then return 2 end
        return 0
        """, Long.class);
    private static final DefaultRedisScript<Long> COMPLETE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
          redis.call('SET', KEYS[1], 'DONE', 'PX', ARGV[2])
          return 1
        end
        return 0
        """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
        return 0
        """, Long.class);
    private final StringRedisTemplate redis;
    public RedisIdempotencyStore(StringRedisTemplate redis) { this.redis = redis; }

    @Override public Claim claim(String key, String owner, Duration lease) {
        Long result = redis.execute(CLAIM, List.of(key), token(owner), millis(lease));
        if (result == null) throw new IllegalStateException("Redis returned no claim result");
        return switch (result.intValue()) {
            case 1 -> Claim.ACQUIRED;
            case 2 -> Claim.COMPLETED;
            case 0 -> Claim.BUSY;
            default -> throw new IllegalStateException("Invalid claim result");
        };
    }
    @Override public boolean complete(String key, String owner, Duration retention) {
        return checked(redis.execute(COMPLETE, List.of(key), token(owner), millis(retention)));
    }
    @Override public boolean release(String key, String owner) {
        return checked(redis.execute(RELEASE, List.of(key), token(owner)));
    }
    private static boolean checked(Long result) {
        if (result == null) throw new IllegalStateException("Redis returned no transition result");
        return result == 1;
    }
    private static String token(String owner) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("Owner is required");
        return "PROCESSING:" + owner;
    }
    private static String millis(Duration duration) {
        long value = duration.toMillis();
        if (value <= 0) throw new IllegalArgumentException("Duration must be at least 1ms");
        return Long.toString(value);
    }
}
