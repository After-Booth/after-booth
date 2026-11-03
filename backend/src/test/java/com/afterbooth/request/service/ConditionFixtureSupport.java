package com.afterbooth.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.afterbooth.ai.AiGateway;
import com.afterbooth.inventory.domain.ItemCategory;
import com.afterbooth.inventory.domain.VehicleType;
import com.afterbooth.request.domain.ConditionContext;
import com.afterbooth.request.repository.RequestReferences;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;

final class ConditionFixtureSupport {
  private static com.afterbooth.global.security.DomainAccess syntheticAccess() {
    var access = org.mockito.Mockito.mock(com.afterbooth.global.security.DomainAccess.class);
    org.mockito.Mockito.when(access.actor())
        .thenReturn(
            new com.afterbooth.global.security.AuthenticatedMember(
                1L, java.util.UUID.randomUUID(), 0));
    return access;
  }

  private ConditionFixtureSupport() {}

  static void run(String mode) throws Exception {
    byte[] wav = Files.readAllBytes(Path.of("../spike/samples/audio-receiver-racks-sedan.wav"));
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(GatewayConfiguration.class)
        .withPropertyValues("afterbooth.ai.mode=" + mode)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              ConditionService service =
                  new ConditionService(
                      context.getBean(AiGateway.class),
                      mock(RequestReferences.class),
                      Clock.fixed(Instant.parse("2026-10-31T03:00:00Z"), ZoneOffset.UTC),
                      "minimal",
                      "2026-10-31",
                      syntheticAccess(),
                      new com.afterbooth.global.security.MemberWorkRegistry());
              var result =
                  service.interpret(ConditionContext.RECEIVER, null, wav, "audio/wav", null);
              assertThat(result.transcript()).isNotBlank();
              assertThat(result.conditions().category()).isEqualTo(ItemCategory.DISPLAY_RACK);
              assertThat(result.conditions().vehicleType()).isEqualTo(VehicleType.SEDAN);
              assertThat(result.conditions().purpose()).isNotBlank();
              assertThat(result.missingFields()).isEmpty();
            });
  }

  @TestConfiguration(proxyBeanMethods = false)
  @ComponentScan("com.afterbooth.ai")
  static class GatewayConfiguration {}
}
