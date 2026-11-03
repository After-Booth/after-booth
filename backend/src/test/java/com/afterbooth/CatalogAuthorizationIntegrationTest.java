package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.afterbooth.global.security.AuthenticatedMember;
import com.afterbooth.global.security.JwtService;
import com.afterbooth.intake.pipeline.IntakePipeline;
import com.afterbooth.intake.service.IntakeProcessingStore;
import com.afterbooth.request.domain.ConditionContext;
import com.afterbooth.request.service.ConditionService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import(TestcontainersConfiguration.class)
class CatalogAuthorizationIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtService jwt;
  @Autowired Clock clock;
  @Autowired JsonMapper json;
  @Autowired IntakeProcessingStore store;
  @Autowired ConditionService conditions;
  @MockitoBean IntakePipeline pipeline;

  @MockitoBean(name = "intakeExecutor")
  org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor;

  private AuthenticatedMember owner;
  private AuthenticatedMember other;
  private long organization;
  private long event;

  @DynamicPropertySource
  static void keys(DynamicPropertyRegistry registry) {
    SyntheticAuthSupport.configure(registry);
  }

  @BeforeEach
  void setup() {
    organization =
        jdbc.queryForObject(
            "insert into organization(name) values('Synthetic private organization') returning id",
            Long.class);
    owner = DomainAuthTestSupport.seedOwner(jdbc, clock, organization);
    long otherOrganization =
        jdbc.queryForObject(
            "insert into organization(name) values('Another synthetic organization') returning id",
            Long.class);
    other = DomainAuthTestSupport.seedOwner(jdbc, clock, otherOrganization);
    event =
        jdbc.queryForObject(
            "insert into event(organization_id,name,venue,teardown_deadline) values(?,'Private event','Private venue',statement_timestamp()+interval '1 day') returning id",
            Long.class,
            organization);
  }

  private String token(AuthenticatedMember actor) {
    DomainAuthTestSupport.use(actor);
    return DomainAuthTestSupport.bearer(jwt, clock);
  }

  @Test
  void currentMemberIsReadOnlyAndContainsOnlyContractedFields() throws Exception {
    var response =
        mvc.perform(get("/api/members/me").header("Authorization", token(owner)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var tree = json.readTree(response.getContentAsString());
    assertThat(tree.size()).isEqualTo(4);
    assertThat(tree.path("id").asLong()).isEqualTo(owner.memberId());
    assertThat(tree.path("organizationId").asLong()).isEqualTo(organization);
    assertThat(tree.path("state").asText()).isEqualTo("ACTIVE");
    assertThat(response.getContentAsString()).doesNotContain("password", "token", "hash");
    mvc.perform(
            patch("/api/members/me")
                .header("Authorization", token(owner))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().is4xxClientError());
    jdbc.update(
        "update refresh_session set revoked_at=statement_timestamp() where id=?",
        owner.sessionId());
    mvc.perform(get("/api/members/me").header("Authorization", token(owner)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void privateCatalogAndUnclaimedSeedDataAreNotExposed() throws Exception {
    mvc.perform(get("/api/events/" + event).header("Authorization", token(other)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/events").header("Authorization", token(other)))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
    mvc.perform(get("/api/events/" + event).header("Authorization", token(owner)))
        .andExpect(status().isOk());
    var response =
        mvc.perform(get("/api/organizations").header("Authorization", token(owner)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var organizations = json.readTree(response.getContentAsString());
    assertThat(organizations.size()).isEqualTo(1);
    assertThat(organizations.get(0).path("id").asLong()).isEqualTo(organization);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from organization where primary_member_id is null", Integer.class))
        .isPositive();
    SecurityContextHolder.clearContext();
    mvc.perform(get("/api/events")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/health")).andExpect(status().isOk());
  }

  @Test
  void eventCreationDerivesOwnerAndReplaysOnlyForItsActor() throws Exception {
    UUID key = UUID.randomUUID();
    String body =
        "{\"name\":\"New event\",\"venue\":\"Synthetic venue\",\"teardownDeadline\":\"2099-11-04T09:00:00+09:00\"}";
    var first =
        mvc.perform(
                post("/api/events")
                    .header("Authorization", token(owner))
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    var created = json.readTree(first.getContentAsString());
    assertThat(created.path("organizationId").asLong()).isEqualTo(organization);
    mvc.perform(
            post("/api/events")
                .header("Authorization", token(owner))
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(content().json(first.getContentAsString()));
    mvc.perform(
            post("/api/events")
                .header("Authorization", token(other))
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict());
    for (String invalid :
        java.util.List.of(
            body.replace("New event", " "),
            body.replace("2099-11-04", "2000-11-04"),
            body.replace("New event", "x".repeat(101)),
            body.replace("Synthetic venue", "x".repeat(201)),
            body.substring(0, body.length() - 1) + ",\"organizationId\":" + organization + "}")) {
      mvc.perform(
              post("/api/events")
                  .header("Authorization", token(owner))
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content(invalid))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void catalogHidesRawConditionsAndInactiveProviders() throws Exception {
    long item =
        jdbc.queryForObject(
            "insert into item(event_id,organization_id,category,label,width_cm,depth_cm,height_cm,condition,total_qty,available_qty,pickup_deadline,conditions_transcript) values(?,?,'STORAGE_BIN','Synthetic bin',20,20,20,'GOOD',2,2,statement_timestamp()+interval '1 day','Private giver input') returning id",
            Long.class,
            event,
            organization);
    mvc.perform(get("/api/items/" + item).header("Authorization", token(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conditionsTranscript").value("Private giver input"));
    mvc.perform(get("/api/items/" + item).header("Authorization", token(other)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conditionsTranscript").doesNotExist());
    jdbc.update(
        "update member set state='WITHDRAWN',withdrawn_at=statement_timestamp(),purge_after=statement_timestamp()+interval '720 hours',email_normalized=null,password_hash=null where id=?",
        owner.memberId());
    mvc.perform(get("/api/items/" + item).header("Authorization", token(other)))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/items")
                .param("eventId", Long.toString(event))
                .header("Authorization", token(other)))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  void ownedEventCanStartIntakeAndForeignCallerCannotReadOrReplayIt() throws Exception {
    var output = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
    var photo = new MockMultipartFile("photo", "synthetic.png", "image/png", output.toByteArray());
    UUID key = UUID.randomUUID();
    var first =
        mvc.perform(
                multipart("/api/intake/drafts")
                    .file(photo)
                    .param("eventId", Long.toString(event))
                    .header("Authorization", token(owner))
                    .header("Idempotency-Key", key))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse();
    long draft = json.readTree(first.getContentAsString()).path("id").asLong();
    mvc.perform(get("/api/intake/drafts/" + draft).header("Authorization", token(other)))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/intake/drafts/{id}/confirm", draft)
                .header("Authorization", token(other))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            multipart("/api/intake/drafts")
                .file(photo)
                .param("eventId", Long.toString(event))
                .header("Authorization", token(other))
                .header("Idempotency-Key", key))
        .andExpect(status().isNotFound());
    DomainAuthTestSupport.use(owner);
    store.ready(draft, java.util.List.of(), owner);
    mvc.perform(get("/api/intake/drafts/" + draft).header("Authorization", token(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY"));
    jdbc.update("update member set auth_version=auth_version+1 where id=?", owner.memberId());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.fail(draft, "Synthetic", owner))
        .isInstanceOf(com.afterbooth.global.error.BusinessException.class);
    assertThat(
            jdbc.queryForObject("select status from intake_draft where id=?", String.class, draft))
        .isEqualTo("READY");
  }

  @Test
  void forgedRequestOrganizationAndPrivateStatsFiltersFail() throws Exception {
    String body =
        "{\"organizationId\":"
            + organization
            + ",\"category\":\"DISPLAY_RACK\",\"quantity\":1,\"vehicleType\":\"VAN\"}";
    mvc.perform(
            post("/api/pickup-requests")
                .header("Authorization", token(other))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isNotFound());
    var response =
        mvc.perform(
                post("/api/pickup-requests")
                    .header("Authorization", token(owner))
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    long request = json.readTree(response.getContentAsString()).path("id").asLong();
    mvc.perform(
            post("/api/pickup-requests")
                .header("Authorization", token(owner))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(
                    body.replace(
                        "\"organizationId\":" + organization,
                        "\"organizationId\":9223372036854775807")))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/pickup-requests/" + request).header("Authorization", token(other)))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/stats/reuse")
                .param("eventId", Long.toString(event))
                .header("Authorization", token(other)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/stats/reuse").header("Authorization", token(other)))
        .andExpect(status().isOk());
    DomainAuthTestSupport.use(other);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                conditions.interpret(
                    ConditionContext.GIVER, event, null, null, "Synthetic conditions"))
        .isInstanceOf(com.afterbooth.global.error.BusinessException.class);
  }
}
