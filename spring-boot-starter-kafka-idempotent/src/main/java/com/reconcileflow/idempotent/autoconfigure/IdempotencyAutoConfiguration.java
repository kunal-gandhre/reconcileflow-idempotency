package com.reconcileflow.idempotent.autoconfigure;

import com.reconcileflow.idempotent.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration(after = RedisAutoConfiguration.class)
@ConditionalOnProperty(prefix = "reconcileflow", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class IdempotencyAutoConfiguration {
    @Bean @ConditionalOnMissingBean(IdempotencyStore.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    IdempotencyStore idempotencyStore(StringRedisTemplate redis) { return new RedisIdempotencyStore(redis); }
    @Bean @ConditionalOnMissingBean(IdempotentAspect.class)
    IdempotentAspect idempotentAspect(IdempotencyStore store) { return new IdempotentAspect(store); }
}
