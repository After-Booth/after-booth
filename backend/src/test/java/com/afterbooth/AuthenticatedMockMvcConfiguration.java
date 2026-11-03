package com.afterbooth;

import com.afterbooth.global.security.JwtService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@TestConfiguration(proxyBeanMethods = false)
public class AuthenticatedMockMvcConfiguration {
  @Bean
  DynamicPropertyRegistrar syntheticJwtProperties() {
    return SyntheticAuthSupport::configure;
  }

  @Bean
  MockMvcBuilderCustomizer genuineMemberAuthentication(
      JdbcTemplate jdbc,
      JwtService jwt,
      java.time.Clock clock,
      org.springframework.security.crypto.password.PasswordEncoder encoder) {
    String hash = encoder.encode(java.util.UUID.randomUUID().toString());
    return builder ->
        builder.defaultRequest(
            MockMvcRequestBuilders.get("/")
                .with(
                    request -> {
                      if (request.getHeader("Authorization") == null)
                        request.addHeader(
                            "Authorization", SyntheticAuthSupport.bearer(jdbc, jwt, clock, hash));
                      return request;
                    }));
  }
}
