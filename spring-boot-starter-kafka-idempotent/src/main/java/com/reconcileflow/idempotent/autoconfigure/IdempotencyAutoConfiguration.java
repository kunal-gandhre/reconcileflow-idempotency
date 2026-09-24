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
