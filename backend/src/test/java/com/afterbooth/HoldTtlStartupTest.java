package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;

import com.afterbooth.allocation.repository.ReservationRepository;
import com.afterbooth.allocation.repository.TransitionRepository;
import com.afterbooth.allocation.service.ReservationService;
import com.afterbooth.inventory.service.ItemStockService;
import com.afterbooth.inventory.service.VehicleFitPolicy;
import com.afterbooth.request.service.PickupRequestService;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The hold TTL is validated when the ReservationService bean is created, so a nonpositive
 * afterbooth.hold-ttl fails startup before requests can be served. This slice holds no database,
 * Docker or web beans, so any startup failure can only come from the TTL validation.
 */
class HoldTtlStartupTest {
  ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(
              context ->
                  context
                      .getBeanFactory()
                      .setConversionService(ApplicationConversionService.getSharedInstance()))
          .withUserConfiguration(Slice.class);

  @TestConfiguration
  @Import(ReservationService.class)
  static class Slice {
    @Bean
    ReservationRepository reservations() {
      return Mockito.mock(ReservationRepository.class);
    }

    @Bean
    TransitionRepository transitions() {
      return Mockito.mock(TransitionRepository.class);
    }

    @Bean
    ItemStockService items() {
      return Mockito.mock(ItemStockService.class);
    }

    @Bean
    PickupRequestService requests() {
      return Mockito.mock(PickupRequestService.class);
    }

    @Bean
    VehicleFitPolicy vehicles() {
      return Mockito.mock(VehicleFitPolicy.class);
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }

  void expectTtlStartupFailure(String ttl) {
    runner
        .withPropertyValues("afterbooth.hold-ttl=" + ttl)
        .run(
            context -> {
              assertThat(context.getStartupFailure())
                  .isNotNull()
                  .hasStackTraceContaining("ReservationService");
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("afterbooth.hold-ttl")
                  .hasMessageContaining("positive");
            });
  }

  @Test
  void zeroHoldTtlFailsStartup() {
    expectTtlStartupFailure("PT0S");
  }

  @Test
  void negativeHoldTtlFailsStartup() {
    expectTtlStartupFailure("PT-1S");
  }

  @Test
  void defaultHoldTtlStarts() {
    runner.run(
        context -> {
          assertThat(context.getStartupFailure()).isNull();
          assertThat(context.getBean(ReservationService.class)).isNotNull();
        });
  }

  @Test
  void positiveHoldTtlOverrideStarts() {
    runner
        .withPropertyValues("afterbooth.hold-ttl=PT30S")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              assertThat(context.getBean(ReservationService.class)).isNotNull();
            });
  }
}
