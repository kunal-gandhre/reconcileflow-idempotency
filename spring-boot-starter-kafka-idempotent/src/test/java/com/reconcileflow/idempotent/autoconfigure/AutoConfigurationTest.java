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
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/**
 * Loads small isolated Spring contexts to verify starter registration and override behavior.
 * Mocked Redis infrastructure keeps these wiring tests independent of external services.
 *
 * @author Kunal Gandhre
 */
class AutoConfigurationTest {
    ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class));
    /** Redis infrastructure should create exactly one store and aspect. */
    @Test void wiresRedisAndAspect() { runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class)).run(c -> {
        assertThat(c).hasSingleBean(IdempotencyStore.class).hasSingleBean(IdempotentAspect.class);
    }); }
    /** An application-provided store must take precedence over the default adapter. */
    @Test void respectsCustomStore() { var store = mock(IdempotencyStore.class); runner.withBean(IdempotencyStore.class, () -> store).run(c -> {
        assertThat(c.getBean(IdempotencyStore.class)).isSameAs(store);
        assertThat(c).hasSingleBean(IdempotentAspect.class);
    }); }
    /** The opt-out property removes the interception aspect. */
    /** Enabling protection without a store must fail startup, not silently disable protection. */
    @Test void canDisable() { runner.withPropertyValues("reconcileflow.enabled=false").run(c -> assertThat(c).doesNotHaveBean(IdempotentAspect.class)); }
    @Test void missingStoreFailsStartup() { runner.run(c -> assertThat(c).hasFailed()); }
}
