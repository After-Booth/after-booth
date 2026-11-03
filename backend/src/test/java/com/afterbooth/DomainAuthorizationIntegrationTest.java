package com.afterbooth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.afterbooth.ai.*;
import com.afterbooth.allocation.dto.HoldItemRequest;
import com.afterbooth.allocation.service.ReservationService;
import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.AuthenticatedMember;
import com.afterbooth.global.security.JwtService;
import com.afterbooth.member.dto.*;
import com.afterbooth.member.mail.*;
import com.afterbooth.member.service.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {"spring.docker.compose.enabled=false", "afterbooth.reservation-sweep-ms=3600000"})
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import(TestcontainersConfiguration.class)
class DomainAuthorizationIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired SignupService signup;
  @Autowired SessionService sessions;
  @Autowired JwtService jwt;
  @Autowired Clock clock;
  @Autowired JsonMapper json;
  @Autowired ReservationService reservations;
  @Autowired com.afterbooth.allocation.service.AllocationService allocations;
  @Autowired com.afterbooth.global.idempotency.IdempotencyService idempotency;
  @Autowired PlatformTransactionManager manager;
  @MockitoBean MailGateway mail;
  @MockitoBean AiGateway ai;

  @MockitoBean(name = "intakeExecutor")
  org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor intakeExecutor;

  final BlockingQueue<MailMessage> mailbox = new LinkedBlockingQueue<>();
  Account giver, receiver, other;
  long event, item, request;

  @DynamicPropertySource
  static void keys(DynamicPropertyRegistry registry) {
    SyntheticAuthSupport.configure(registry);
    byte[] key = new byte[32];
    new java.security.SecureRandom().nextBytes(key);
    registry.add("afterbooth.auth.hmac.current-version", () -> "domain-tests");
    registry.add(
        "afterbooth.auth.hmac.keys.domain-tests", () -> Base64.getEncoder().encodeToString(key));
  }

  @BeforeEach
  void setup(org.junit.jupiter.api.TestInfo test) throws Exception {
    if (test.getTestMethod()
        .map(java.lang.reflect.Method::getName)
        .orElse("")
        .equals("allProtectedDomainRoutesRejectAnonymousRequests")) return;
    doAnswer(
            call -> {
              mailbox.add(call.getArgument(0));
              return null;
            })
        .when(mail)
        .send(any());
    giver = account();
    receiver = account();
    other = account();
    event =
        jdbc.queryForObject(
            "insert into event(organization_id,name,venue,teardown_deadline) values(?,'Synthetic event','Synthetic venue',statement_timestamp()+interval '1 day') returning id",
            Long.class,
            giver.organization());
    item = item(giver.organization(), event, 3);
    request = request(receiver.organization(), 2);
    use(receiver);
  }

  Account account() throws Exception {
    String email = UUID.randomUUID() + "@example.invalid", password = UUID.randomUUID().toString();
    signup.register(new RegisterMemberRequest(email, password));
    var challenge = signup.requestVerification(email);
    var message = mailbox.poll(5, TimeUnit.SECONDS);
    if (message == null) throw new AssertionError("Synthetic mail was not delivered");
    signup.verify(new VerifyEmailRequest(email, challenge.challengeId(), message.code(), password));
    var issued = sessions.login(new LoginMemberRequest(email, password), null);
    var actor = jwt.verify(issued.response().accessToken(), clock.instant());
    long organization =
        jdbc.queryForObject(
            "select id from organization where primary_member_id=?", Long.class, actor.memberId());
    return new Account(actor, organization, issued.response().accessToken());
  }

  record Account(AuthenticatedMember actor, long organization, String token) {}

  void use(Account account) {
    DomainAuthTestSupport.use(account.actor());
  }

  String token(Account account) {
    return "Bearer " + account.token();
  }

  long item(long org, long event, int qty) {
    return jdbc.queryForObject(
        "insert into item(event_id,organization_id,category,label,width_cm,depth_cm,height_cm,condition,total_qty,available_qty,pickup_deadline) values(?,?,'STORAGE_BIN','Synthetic bin',20,20,20,'GOOD',?,?,statement_timestamp()+interval '1 hour') returning id",
        Long.class,
        event,
        org,
        qty,
        qty);
  }

  long request(long org, int qty) {
    return jdbc.queryForObject(
        "insert into pickup_request(organization_id,category,quantity,vehicle_type,created_at) values(?,'STORAGE_BIN',?,'VAN',statement_timestamp()) returning id",
        Long.class,
        org,
        qty);
  }

  long hold() {
    use(receiver);
    return reservations.hold(new HoldItemRequest(request, item, 1)).id();
  }

  String holdBody(long req, long stock) {
    return "{\"requestId\":" + req + ",\"itemId\":" + stock + ",\"quantity\":1}";
  }

  @Test
  void registeredAndLoggedInMemberCreatesEventAndStartsPhotoIntake() throws Exception {
    String body =
        "{\"name\":\"New private event\",\"venue\":\"Synthetic venue\",\"teardownDeadline\":\""
            + clock.instant().plusSeconds(3600)
            + "\"}";
    var response =
        mvc.perform(
                post("/api/events")
                    .header("Authorization", token(other))
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    long id = json.readTree(response.getContentAsString()).path("id").asLong();
    mvc.perform(
            multipart("/api/intake/drafts")
                .file(
                    new org.springframework.mock.web.MockMultipartFile(
                        "photo", "synthetic.png", "image/png", png()))
                .param("eventId", Long.toString(id))
                .header("Authorization", token(other))
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isAccepted());
    assertThat(jdbc.queryForObject("select organization_id from event where id=?", Long.class, id))
        .isEqualTo(other.organization());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from organization where id in(1,2) and primary_member_id is null",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void anonymousDomainAccessFailsWhileHealthRemainsPublic() throws Exception {
    mvc.perform(get("/api/health")).andExpect(status().isOk());
    mvc.perform(get("/api/members/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/reservations")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/reservations")
                .contentType("application/json")
                .content(holdBody(request, item))
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void reservationsAreVisibleOnlyToPartiesAndReceiverAloneCanConfirmOrCancel() throws Exception {
    long id = hold();
    for (Account party : List.of(giver, receiver))
      mvc.perform(get("/api/reservations/{id}", id).header("Authorization", token(party)))
          .andExpect(status().isOk());
    mvc.perform(get("/api/reservations/{id}", id).header("Authorization", token(other)))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/reservations")
                .param("requestId", Long.toString(request))
                .param("eventId", Long.toString(event))
                .header("Authorization", token(other)))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
    mvc.perform(
            post("/api/reservations/{id}/confirm", id)
                .header("Authorization", token(giver))
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/reservations/{id}/cancel", id)
                .header("Authorization", token(giver))
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/reservations/{id}/confirm", id)
                .header("Authorization", token(other))
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/reservations/{id}/cancel", id)
                .header("Authorization", token(other))
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/reservations/{id}/confirm", id)
                .header("Authorization", token(receiver))
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/reservations/{id}/cancel", id)
                .header("Authorization", token(receiver))
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isOk());
  }

  @Test
  void handoverSubmissionAndResolutionRequireGiverBeforeModelExecution() throws Exception {
    long id = hold();
    use(receiver);
    reservations.confirm(id);
    for (Account denied : List.of(receiver, other)) {
      int expected = denied == receiver ? 403 : 404;
      mvc.perform(
              multipart("/api/reservations/{id}/handover", id)
                  .file(
                      new org.springframework.mock.web.MockMultipartFile(
                          "photo", "synthetic.png", "image/png", png()))
                  .header("Authorization", token(denied))
                  .header("Idempotency-Key", UUID.randomUUID()))
          .andExpect(status().is(expected));
      mvc.perform(
              post("/api/reservations/{id}/handover/resolve", id)
                  .header("Authorization", token(denied))
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content("{\"acceptedQty\":1}"))
          .andExpect(status().is(expected));
    }
    verifyNoInteractions(ai);
    mvc.perform(
            post("/api/reservations/{id}/handover/resolve", id)
                .header("Authorization", token(giver))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content("{\"acceptedQty\":1}"))
        .andExpect(status().isConflict());
  }

  @Test
  void authenticatedReplayNeverCrossesActorsOrClaimsLegacyRecords() throws Exception {
    UUID key = UUID.randomUUID();
    String body = holdBody(request, item);
    for (int i = 0; i < 2; i++)
      mvc.perform(
              post("/api/reservations")
                  .header("Authorization", token(receiver))
                  .header("Idempotency-Key", key)
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isCreated());
    assertThat(
            jdbc.queryForObject("select available_qty from item where id=?", Integer.class, item))
        .isEqualTo(2);
    long otherRequest = request(other.organization(), 1);
    mvc.perform(
            post("/api/reservations")
                .header("Authorization", token(other))
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(holdBody(otherRequest, item)))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/reservations")
                .header("Authorization", token(other))
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isNotFound());
    assertThat(
            jdbc.queryForObject(
                "select actor_member_id from idempotency_record where idempotency_key=?",
                Long.class,
                key))
        .isEqualTo(receiver.actor().memberId());
    jdbc.update("update idempotency_record set actor_member_id=null where idempotency_key=?", key);
    mvc.perform(
            post("/api/reservations")
                .header("Authorization", token(receiver))
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  void allocationTracksEveryProviderAndReceiverForReplayInvalidation() throws Exception {
    long otherEvent =
        jdbc.queryForObject(
            "insert into event(organization_id,name,venue,teardown_deadline) values(?,'Other event','Synthetic',statement_timestamp()+interval '1 day') returning id",
            Long.class,
            other.organization());
    long second = item(other.organization(), otherEvent, 1);
    AtomicInteger turn = new AtomicInteger();
    when(ai.chat(any()))
        .thenAnswer(
            call -> {
              int n = turn.getAndIncrement();
              return n < 2
                  ? new ChatResponse(
                      null,
                      List.of(
                          new ToolCall(
                              UUID.randomUUID().toString(),
                              "holdQuantity",
                              "{\"itemId\":" + (n == 0 ? item : second) + ",\"quantity\":1}")),
                      "tool_calls",
                      null)
                  : new ChatResponse("완료했어요.", List.of(), "stop", null);
            });
    UUID key = UUID.randomUUID();
    mvc.perform(
            post("/api/pickup-requests/{id}/allocation", request)
                .header("Authorization", token(receiver))
                .header("Idempotency-Key", key))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("HELD"));
    List<Long> ids =
        jdbc.queryForList(
            "select unnest(related_organization_ids) from idempotency_record where idempotency_key=?",
            Long.class,
            key);
    assertThat(ids).contains(giver.organization(), receiver.organization(), other.organization());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reservation where request_id=?", Integer.class, request))
        .isEqualTo(2);
  }

  @Test
  void finalMemberLockRejectsRevokedPreparationWithoutChangingStock() throws Exception {
    queuedWrite(false, () -> reservations.hold(new HoldItemRequest(request, item, 1)));
  }

  @Test
  void finalAllocationMemberLockRejectsWithdrawalAfterModelPreparation() throws Exception {
    proposeOne();
    use(receiver);
    var prepared = allocations.prepare(request);
    queuedWrite(true, prepared);
  }

  private void queuedWrite(boolean withdrawn, java.util.function.Supplier<?> action)
      throws Exception {
    CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
    use(receiver);
    try (var pool =
        com.afterbooth.DomainAuthTestSupport.executor(Executors.newFixedThreadPool(2))) {
      var blocker =
          pool.submit(
              () ->
                  new TransactionTemplate(manager)
                      .executeWithoutResult(
                          tx -> {
                            jdbc.queryForList(
                                "select id from member where id=? for update",
                                giver.actor().memberId());
                            locked.countDown();
                            await(release);
                            if (withdrawn)
                              jdbc.update(
                                  "update member set state='WITHDRAWN',email_normalized=null,password_hash=null,withdrawn_at=statement_timestamp(),purge_after=statement_timestamp()+interval '720 hours' where id=?",
                                  receiver.actor().memberId());
                            else
                              jdbc.update(
                                  "update refresh_session set revoked_at=statement_timestamp() where id=?",
                                  receiver.actor().sessionId());
                          }));
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
      var write =
          pool.submit(
              () -> {
                try {
                  action.get();
                  return (ErrorCode) null;
                } catch (BusinessException e) {
                  return e.errorCode();
                }
              });
      try {
        waitForMemberLock();
      } finally {
        release.countDown();
      }
      blocker.get(10, TimeUnit.SECONDS);
      assertThat(write.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorCode.UNAUTHENTICATED);
    }
    assertThat(
            jdbc.queryForObject("select available_qty from item where id=?", Integer.class, item))
        .isEqualTo(3);
  }

  @Test
  void finalizerAndIdempotentResponseRollBackTogetherWhenDoneWriteFails() throws Exception {
    proposeOne();
    use(receiver);
    UUID key = UUID.randomUUID();
    jdbc.execute(
        "create function synthetic_fail_done() returns trigger language plpgsql as $$ begin if NEW.state='DONE' then raise exception 'Synthetic final response failure'; end if; return NEW; end $$");
    jdbc.execute(
        "create trigger synthetic_fail_done before update on idempotency_record for each row execute function synthetic_fail_done()");
    try {
      assertThatThrownBy(
              () ->
                  idempotency.executePrepared(
                      key,
                      "POST",
                      "/api/pickup-requests/" + request + "/allocation",
                      new byte[0],
                      200,
                      () -> allocations.prepare(request)))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally {
      jdbc.execute("drop trigger synthetic_fail_done on idempotency_record");
      jdbc.execute("drop function synthetic_fail_done()");
    }
    assertThat(
            jdbc.queryForObject("select available_qty from item where id=?", Integer.class, item))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reservation where request_id=?", Integer.class, request))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from idempotency_record where idempotency_key=?",
                Integer.class,
                key))
        .isZero();
  }

  @Test
  void noMatchReplayRejectsWithdrawnAlternativeProvider() throws Exception {
    when(ai.chat(any())).thenReturn(new ChatResponse("대안을 확인해 주세요.", List.of(), "stop", null));
    UUID key = UUID.randomUUID();
    mvc.perform(
            post("/api/pickup-requests/{id}/allocation", request)
                .header("Authorization", token(receiver))
                .header("Idempotency-Key", key))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NO_MATCH"));
    assertThat(
            jdbc.queryForList(
                "select unnest(related_organization_ids) from idempotency_record where idempotency_key=?",
                Long.class,
                key))
        .contains(giver.organization(), receiver.organization());
    jdbc.update(
        "update member set state='WITHDRAWN',email_normalized=null,password_hash=null,withdrawn_at=statement_timestamp(),purge_after=statement_timestamp()+interval '720 hours' where id=?",
        giver.actor().memberId());
    mvc.perform(
            post("/api/pickup-requests/{id}/allocation", request)
                .header("Authorization", token(receiver))
                .header("Idempotency-Key", key))
        .andExpect(status().isNotFound());
  }

  @Test
  void unsupportedMutationAliasesCannotReplayOrPersistPrivateResponses() throws Exception {
    long id = hold();
    UUID key = UUID.randomUUID();
    mvc.perform(
            post("/api/reservations/{id}/confirm", id)
                .header("Authorization", token(receiver))
                .header("Idempotency-Key", key))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/reservations/+" + id + "/confirm")
                .header("Authorization", token(other))
                .header("Idempotency-Key", key))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/reservations/+" + id + "/cancel")
                .header("Authorization", token(other))
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isForbidden());
  }

  private void proposeOne() {
    AtomicInteger turn = new AtomicInteger();
    when(ai.chat(any()))
        .thenAnswer(
            call ->
                turn.getAndIncrement() == 0
                    ? new ChatResponse(
                        null,
                        List.of(
                            new ToolCall(
                                "synthetic-hold",
                                "holdQuantity",
                                "{\"itemId\":" + item + ",\"quantity\":1}")),
                        "tool_calls",
                        null)
                    : new ChatResponse("완료했어요.", List.of(), "stop", null));
  }

  @Test
  void unrelatedMemberCannotRunAnotherReceiversAllocation() throws Exception {
    mvc.perform(
            post("/api/pickup-requests/{id}/allocation", request)
                .header("Authorization", token(other))
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isNotFound());
    verifyNoInteractions(ai);
  }

  @Test
  void giverInterpretationRequiresOwnEventAndReceiverInterpretationRequiresActiveMember()
      throws Exception {
    mvc.perform(
            multipart("/api/conditions/interpret")
                .param("context", "GIVER")
                .param("eventId", Long.toString(event))
                .param("text", "Synthetic conditions")
                .header("Authorization", token(other)))
        .andExpect(status().isNotFound());
    verifyNoInteractions(ai);
    when(ai.chat(any()))
        .thenReturn(new ChatResponse("{\"purpose\":\"\"}", List.of(), "stop", null));
    mvc.perform(
            multipart("/api/conditions/interpret")
                .param("context", "GIVER")
                .param("eventId", Long.toString(event))
                .param("text", "Synthetic conditions")
                .header("Authorization", token(giver)))
        .andExpect(status().isOk());
    mvc.perform(
            multipart("/api/conditions/interpret")
                .param("context", "RECEIVER")
                .param("text", "Synthetic conditions")
                .header("Authorization", token(receiver)))
        .andExpect(status().isOk());
    jdbc.update(
        "update refresh_session set revoked_at=statement_timestamp() where id=?",
        receiver.actor().sessionId());
    mvc.perform(
            multipart("/api/conditions/interpret")
                .param("context", "RECEIVER")
                .param("text", "Synthetic conditions")
                .header("Authorization", token(receiver)))
        .andExpect(status().isUnauthorized());
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{0} {1} rejects an anonymous caller")
  @org.junit.jupiter.params.provider.MethodSource("protectedDomainRoutes")
  void allProtectedDomainRoutesRejectAnonymousRequests(String method, String path)
      throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                    org.springframework.http.HttpMethod.valueOf(method), java.net.URI.create(path))
                .contentType("application/json")
                .content("{}")
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments>
      protectedDomainRoutes() {
    List<org.junit.jupiter.params.provider.Arguments> cases = new ArrayList<>();
    for (String path :
        List.of(
            "/api/members/me",
            "/api/organizations",
            "/api/events",
            "/api/events/1",
            "/api/items",
            "/api/items/1",
            "/api/intake/drafts/1",
            "/api/pickup-requests/1",
            "/api/reservations",
            "/api/reservations/1",
            "/api/stats/reuse"))
      cases.add(org.junit.jupiter.params.provider.Arguments.of("GET", path));
    for (String path :
        List.of(
            "/api/events",
            "/api/intake/drafts",
            "/api/intake/drafts/1/confirm",
            "/api/conditions/interpret",
            "/api/pickup-requests",
            "/api/pickup-requests/1/allocation",
            "/api/reservations",
            "/api/reservations/1/confirm",
            "/api/reservations/1/cancel",
            "/api/reservations/1/handover",
            "/api/reservations/1/handover/resolve"))
      cases.add(org.junit.jupiter.params.provider.Arguments.of("POST", path));
    return cases.stream();
  }

  private void waitForMemberLock() throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < until) {
      if (Boolean.TRUE.equals(
          jdbc.queryForObject(
              "select exists(select 1 from pg_stat_activity where pid<>pg_backend_pid() and lower(query) like '%from member%' and wait_event_type='Lock' and cardinality(pg_blocking_pids(pid))>0)",
              Boolean.class))) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Final write never waited on the member row");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS))
        throw new AssertionError("Lock coordination timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static byte[] png() throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
        "png",
        out);
    return out.toByteArray();
  }
}
