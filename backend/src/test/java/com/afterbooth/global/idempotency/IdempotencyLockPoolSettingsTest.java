package com.afterbooth.global.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

/** Both Hikari pools stay lazy here, so these checks never open a database connection. */
class IdempotencyLockPoolSettingsTest {
  ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(
              context ->
                  context
                      .getBeanFactory()
                      .setConversionService(ApplicationConversionService.getSharedInstance()))
          .withUserConfiguration(Slice.class);

  @TestConfiguration
  @Import(IdempotencyLockPool.class)
  static class Slice {
    @Bean
    HikariDataSource dataSource() {
      HikariDataSource source = new HikariDataSource();
      source.setJdbcUrl("jdbc:postgresql://localhost:1/unused");
      return source;
    }
  }

  void expectSizes(int maximum, int minimum, String... properties) {
    runner
        .withPropertyValues(properties)
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              var pool =
                  (HikariDataSource)
                      ReflectionTestUtils.getField(
                          context.getBean(IdempotencyLockPool.class), "pool");
              assertThat(pool.getMaximumPoolSize()).isEqualTo(maximum);
              assertThat(pool.getMinimumIdle()).isEqualTo(minimum);
            });
  }

  @Test
  void defaultPoolIsPrefilledToItsMaximum() {
    expectSizes(16, 16);
  }

  @Test
  void maximumOnlyOverrideLowersTheDefaultMinimumWithIt() {
    expectSizes(8, 8, "afterbooth.idempotency.lock-pool.maximum-pool-size=8");
  }

  @Test
  void explicitMinimumStillApplies() {
    expectSizes(
        8,
        2,
        "afterbooth.idempotency.lock-pool.maximum-pool-size=8",
        "afterbooth.idempotency.lock-pool.minimum-idle=2");
  }
}
