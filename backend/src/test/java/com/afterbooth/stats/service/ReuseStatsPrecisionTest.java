package com.afterbooth.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.afterbooth.global.security.DomainAccess;
import com.afterbooth.inventory.domain.ItemCategory;
import com.afterbooth.stats.repository.ReuseStatsRepository;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReuseStatsPrecisionTest {
  private final ReuseStatsRepository repository = mock(ReuseStatsRepository.class);
  private final DomainAccess access = mock(DomainAccess.class);
  private final ReuseStatsService service = new ReuseStatsService(repository, access);

  @Test
  void floorsOnlyAfterCombiningEveryCategoryAndEvent() {
    when(repository.quantities(null))
        .thenReturn(List.of(row(1, BigInteger.valueOf(600)), row(2, BigInteger.valueOf(600))));
    assertThat(service.get(null).reusedVolumeLiters()).isEqualTo(1);
  }

  @Test
  void cubicCentimetersAboveLongRangeRemainExactBeforeDivision() {
    BigInteger volume = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
    when(repository.quantities(null)).thenReturn(List.of(row(1, volume)));
    assertThat(service.get(null).reusedVolumeLiters())
        .isEqualTo(volume.divide(BigInteger.valueOf(1000)).longValueExact());
  }

  @Test
  void unrepresentableContractValueFailsInsteadOfWrapping() {
    when(repository.quantities(null))
        .thenReturn(List.of(row(1, BigInteger.TEN.pow(38).subtract(BigInteger.ONE))));
    assertThatThrownBy(() -> service.get(null)).isInstanceOf(ArithmeticException.class);
  }

  private ReuseStatsRepository.Quantity row(long event, BigInteger volume) {
    return new ReuseStatsRepository.Quantity(
        ItemCategory.STORAGE_BIN, event, "Synthetic event", event, BigInteger.ONE, volume);
  }
}
