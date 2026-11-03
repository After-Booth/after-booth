package com.afterbooth.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afterbooth.global.error.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SignupCredentialsTest {
  @Test
  void normalizationPreservesAliasesAndRejectsAmbiguousInputs() {
    assertThat(SignupCredentials.normalizeEmail(" User@LOCALHOST ")).isEqualTo("user@localhost");
    assertThat(SignupCredentials.normalizeEmail("  Example+tag.Name@EXAMPLE.TEST  "))
        .isEqualTo("example+tag.name@example.test");
    for (String invalid :
        new String[] {
          "a b@example.test",
          "a\t@example.test",
          "a@example.test\n",
          "Name <a@example.test>",
          "a(comment)@example.test",
          "가@example.test",
          "a".repeat(65) + "@example.test"
        })
      assertThatThrownBy(() -> SignupCredentials.normalizeEmail(invalid))
          .isInstanceOf(BusinessException.class);
  }

  @Test
  void passwordUsesCodePointsWithoutTrimmingOrUnicodeNormalization() {
    String value = " " + UUID.randomUUID() + " e\u0301 ";
    assertThat(SignupCredentials.password(value) == value).isTrue();
    assertThat(SignupCredentials.password("😀".repeat(128)).codePointCount(0, 256)).isEqualTo(128);
    assertThatThrownBy(() -> SignupCredentials.password("x".repeat(11)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> SignupCredentials.password("x".repeat(129)))
        .isInstanceOf(BusinessException.class);
  }
}
