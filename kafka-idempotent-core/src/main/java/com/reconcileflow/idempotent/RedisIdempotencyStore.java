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
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Implements the state machine using atomic Lua scripts against one Redis key.
 * PROCESSING tokens have a lease; DONE records have a separate retention period.
 * Single-key scripts work with Redis Cluster routing but do not guarantee failover durability.
 *
 * @author Kunal Gandhre
 */
public final class RedisIdempotencyStore implements IdempotencyStore {
    // KEYS[1] is the storage key; ARGV contains token and TTL in milliseconds.
    // Redis runs this read-and-set atomically: 1 = acquired, 2 = completed, 0 = busy.
    private static final DefaultRedisScript<Long> CLAIM = new DefaultRedisScript<>("""
        local value = redis.call('GET', KEYS[1])
        if not value then
          redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
          return 1
        end
        if value == 'DONE' then return 2 end
        return 0
        """, Long.class);
    // Replace a matching live token with DONE and start the completion retention clock.
    private static final DefaultRedisScript<Long> COMPLETE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
          redis.call('SET', KEYS[1], 'DONE', 'PX', ARGV[2])
          return 1
        end
        return 0
        """, Long.class);
    // Compare-and-delete avoids deleting a replacement claim after the old lease expires.
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
        return 0
        """, Long.class);
    private final StringRedisTemplate redis;
    /** Uses Spring's configured Redis connection, serializers and timeout settings. */
    public RedisIdempotencyStore(StringRedisTemplate redis) { this.redis = redis; }

    /** Maps the script protocol to domain states; missing replies fail closed. */
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
    /** Marks success only while the supplied owner still holds the live claim. */
    @Override public boolean complete(String key, String owner, Duration retention) {
        return checked(redis.execute(COMPLETE, List.of(key), token(owner), millis(retention)));
    }
    /** Releases failed work only when its owner token still matches. */
    @Override public boolean release(String key, String owner) {
        return checked(redis.execute(RELEASE, List.of(key), token(owner)));
    }
    // A null reply is an error, not evidence that another worker owns the key.
    private static boolean checked(Long result) {
        if (result == null) throw new IllegalStateException("Redis returned no transition result");
        return result == 1;
    }
    // Prefix active values so they cannot collide with the reserved DONE marker.
    private static String token(String owner) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("Owner is required");
        return "PROCESSING:" + owner;
    }
    // Redis PX uses positive integer milliseconds, not annotation strings or seconds.
    private static String millis(Duration duration) {
        long value = duration.toMillis();
        if (value <= 0) throw new IllegalArgumentException("Duration must be at least 1ms");
        return Long.toString(value);
    }
}
