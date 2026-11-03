package com.afterbooth.inventory.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.afterbooth.inventory.dto.OrganizationResponse;
import com.afterbooth.inventory.service.OrganizationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@org.springframework.boot.test.context.SpringBootTest(
    properties = "spring.docker.compose.enabled=false")
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(
    print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@org.springframework.context.annotation.Import({
  com.afterbooth.AuthenticatedMockMvcConfiguration.class,
  com.afterbooth.TestcontainersConfiguration.class
})
class OrganizationControllerTest {
  @Autowired MockMvc mvc;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired java.time.Clock clock;
  @MockitoBean OrganizationService organizations;

  @org.junit.jupiter.api.AfterEach
  void clearActor() {
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
  }

  @Test
  void actorFromAnotherDatabaseDoesNotInvalidateDefaultAuthentication() throws Exception {
    com.afterbooth.DomainAuthTestSupport.use(
        new com.afterbooth.global.security.AuthenticatedMember(
            Long.MAX_VALUE, java.util.UUID.randomUUID(), 0));
    when(organizations.list()).thenReturn(List.of());

    mvc.perform(get("/api/organizations")).andExpect(status().isOk());
  }

  @Test
  void requestRestoresTheCurrentDatabaseActor() throws Exception {
    var actor = com.afterbooth.DomainAuthTestSupport.seedOwner(jdbc, clock, 1);
    when(organizations.list()).thenReturn(List.of());

    mvc.perform(get("/api/organizations")).andExpect(status().isOk());

    org.assertj.core.api.Assertions.assertThat(
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal())
        .isEqualTo(actor);
  }

  @Test
  void revokedCurrentDatabaseActorIsNotReplacedWithSyntheticAuthentication() throws Exception {
    var actor = com.afterbooth.DomainAuthTestSupport.seedOwner(jdbc, clock, 1);
    jdbc.update(
        "update refresh_session set revoked_at=clock_timestamp() where id=?", actor.sessionId());

    mvc.perform(get("/api/organizations")).andExpect(status().isUnauthorized());
  }

  @Test
  void invalidCurrentDatabaseActorVersionIsNotReplacedWithSyntheticAuthentication()
      throws Exception {
    var actor = com.afterbooth.DomainAuthTestSupport.seedOwner(jdbc, clock, 1);
    com.afterbooth.DomainAuthTestSupport.use(
        new com.afterbooth.global.security.AuthenticatedMember(
            actor.memberId(), actor.sessionId(), actor.authVersion() + 1));

    mvc.perform(get("/api/organizations")).andExpect(status().isUnauthorized());
  }

  @Test
  void explicitInvalidAuthorizationIsNotReplacedWithSyntheticAuthentication() throws Exception {
    mvc.perform(get("/api/organizations").header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void listsOrganizationsWithOnlyContractFields() throws Exception {
    when(organizations.list())
        .thenReturn(
            List.of(
                new OrganizationResponse(1L, "Giver"), new OrganizationResponse(2L, "Receiver")));

    mvc.perform(get("/api/organizations"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(
            content()
                .json(
                    "[{\"id\":1,\"name\":\"Giver\"},{\"id\":2,\"name\":\"Receiver\"}]",
                    JsonCompareMode.STRICT));
  }

  @Test
  void returnsEmptyArrayWhenThereAreNoOrganizations() throws Exception {
    when(organizations.list()).thenReturn(List.of());

    mvc.perform(get("/api/organizations"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]", JsonCompareMode.STRICT));
  }
}
