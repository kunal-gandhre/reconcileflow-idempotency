package com.reconcileflow.idempotent.autoconfigure;
import com.reconcileflow.idempotent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class AutoConfigurationTest {
    ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class));
    @Test void wiresRedisAndAspect() { runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class)).run(c -> {
        assertThat(c).hasSingleBean(IdempotencyStore.class).hasSingleBean(IdempotentAspect.class);
    }); }
    @Test void respectsCustomStore() { var store = mock(IdempotencyStore.class); runner.withBean(IdempotencyStore.class, () -> store).run(c -> {
        assertThat(c.getBean(IdempotencyStore.class)).isSameAs(store);
        assertThat(c).hasSingleBean(IdempotentAspect.class);
    }); }
    @Test void canDisable() { runner.withPropertyValues("reconcileflow.enabled=false").run(c -> assertThat(c).doesNotHaveBean(IdempotentAspect.class)); }
    @Test void missingStoreFailsStartup() { runner.run(c -> assertThat(c).hasFailed()); }
}
