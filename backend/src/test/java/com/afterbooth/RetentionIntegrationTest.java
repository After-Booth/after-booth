package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afterbooth.inventory.domain.ItemCategory;
import com.afterbooth.member.repository.RetentionRepository;
import com.afterbooth.member.service.RetentionCleanup;
import com.afterbooth.stats.repository.ReuseStatsRepository;
import com.afterbooth.stats.service.ReuseStatsService;
import java.math.BigInteger;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@Import(TestcontainersConfiguration.class)
class RetentionIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired RetentionCleanup cleanup;
  @Autowired RetentionRepository repository;
  @Autowired ReuseStatsRepository statistics;
  @Autowired ReuseStatsService reuse;
  @Autowired PlatformTransactionManager manager;
  @Autowired Clock clock;
  @Autowired com.afterbooth.global.security.MemberWorkRegistry work;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    SyntheticAuthSupport.configure(registry);
  }

  @Test
  void separateSixHundredCubicCentimeterPurgesKeepExactVolumeAndStock() {
    var f = fixture();
    cleanup.clean();
    assertThat(archived(f)).isEqualTo(1);
    assertThat(volume(f)).isEqualTo(BigInteger.valueOf(1200));
    assertThat(
            jdbc.queryForObject(
                "select available_qty=total_qty-archived_handed_over_qty-(select coalesce(sum(handed_over_qty),0) from reservation where item_id=i.id and status='HANDED_OVER') from item i where id=?",
                Boolean.class,
                f.item()))
        .isTrue();
    jdbc.update(
        "update reservation set retention_until=clock_timestamp()-interval '1 second' where id=?",
        f.second());
    cleanup.clean();
    cleanup.clean();
    assertThat(archived(f)).isEqualTo(2);
    assertThat(volume(f).divide(BigInteger.valueOf(1000))).isEqualTo(BigInteger.ONE);
    assertThat(
            jdbc.queryForObject(
                "select reused_qty from reuse_archive where event_id=?", Long.class, f.event()))
        .isEqualTo(2L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pickup_request where id=?", Integer.class, f.request()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from member where id=?", Integer.class, f.receiver()))
        .isZero();
    assertThat(jdbc.queryForObject("select state from member where id=?", String.class, f.giver()))
        .isEqualTo("ACTIVE");
  }

  @Test
  void reuseServiceCombinesSurvivingAndArchivedCategoryAndEventTotals() {
    var f = fixture();
    cleanup.clean();
    long organization =
        jdbc.queryForObject("select organization_id from event where id=?", Long.class, f.event());
    DomainAuthTestSupport.seedOwner(jdbc, clock, organization);
    try {
      var stats = reuse.get(f.event());
      assertThat(stats.totalReused()).isEqualTo(2);
      assertThat(stats.reusedVolumeLiters()).isEqualTo(1);
      assertThat(stats.byCategory())
          .singleElement()
          .satisfies(
              row -> {
                assertThat(row.category()).isEqualTo(ItemCategory.STORAGE_BIN);
                assertThat(row.quantity()).isEqualTo(2);
              });
      assertThat(stats.byEvent())
          .singleElement()
          .satisfies(
              row -> {
                assertThat(row.eventId()).isEqualTo(f.event());
                assertThat(row.quantity()).isEqualTo(2);
              });
    } finally {
      SecurityContextHolder.clearContext();
    }
    assertThat(
            jdbc.queryForObject(
                "select available_qty from item where id=?", Integer.class, f.item()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select available_qty=total_qty-archived_handed_over_qty-(select coalesce(sum(handed_over_qty),0) from reservation where item_id=i.id and status='HANDED_OVER') from item i where id=?",
                Boolean.class,
                f.item()))
        .isTrue();
  }

  @Test
  void rollbackRestoresArchiveInventoryAndAllHistoryThenRetrySucceeds() {
    var f = fixture();
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              repository.cleanup();
              status.setRollbackOnly();
            });
    assertThat(archived(f)).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reservation where id=?", Integer.class, f.first()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reservation_transition where reservation_id=?",
                Integer.class,
                f.first()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from handover_log where reservation_id=?",
                Integer.class,
                f.first()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reuse_archive where event_id=?", Integer.class, f.event()))
        .isZero();
    cleanup.clean();
    assertThat(archived(f)).isEqualTo(1);
  }

  @Test
  void concurrentWorkersDoNotDoubleArchiveAndUnassignedObjectsSurvive() throws Exception {
    var f = fixture();
    long unassigned =
        jdbc.queryForObject(
            "insert into organization(name) values('Synthetic unassigned') returning id",
            Long.class);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var a = workers.submit(() -> cleanup.clean());
      var b = workers.submit(() -> cleanup.clean());
      a.get();
      b.get();
    }
    cleanup.clean();
    assertThat(archived(f)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from organization where id=? and primary_member_id is null",
                Integer.class,
                unassigned))
        .isEqualTo(1);
  }

  @Test
  void archiveShellRemainsWithoutMemberOrOriginalInputs() {
    var f = fixture();
    jdbc.update(
        "update reservation set retention_until=clock_timestamp()-interval '1 second' where item_id=?",
        f.item());
    jdbc.update(
        "update member set state='WITHDRAWN',email_normalized=null,password_hash=null,withdrawn_at=statement_timestamp()-interval '721 hours',purge_after=statement_timestamp()-interval '1 hour' where id=?",
        f.giver());
    jdbc.update("update event set name='Withdrawn event',venue='Removed' where id=?", f.event());
    jdbc.update(
        "update organization set name='Withdrawn organization' where primary_member_id=?",
        f.giver());
    cleanup.clean();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from member where id in (?,?)",
                Integer.class,
                f.giver(),
                f.receiver()))
        .isZero();
    assertThat(jdbc.queryForObject("select count(*) from item where id=?", Integer.class, f.item()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select primary_member_id is null from organization where id=(select organization_id from event where id=?)",
                Boolean.class,
                f.event()))
        .isTrue();
    assertThat(volume(f)).isEqualTo(BigInteger.valueOf(1200));
  }

  @Test
  void deletionFailureRollsBackEarlierArchiveAndInventoryAndNextRunRetries() {
    var f = fixture();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String function = "retention_failure_" + suffix;
    String trigger = "retention_failure_trigger_" + suffix;
    jdbc.execute(
        "CREATE FUNCTION "
            + function
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF OLD.id="
            + f.first()
            + " THEN RAISE EXCEPTION 'Synthetic retention failure'; END IF; RETURN OLD; END $$");
    jdbc.execute(
        "CREATE TRIGGER "
            + trigger
            + " BEFORE DELETE ON reservation FOR EACH ROW EXECUTE FUNCTION "
            + function
            + "()");
    try {
      assertThatThrownBy(() -> cleanup.clean())
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(archived(f)).isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from reuse_archive where event_id=?", Integer.class, f.event()))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from reservation_transition where reservation_id=?",
                  Integer.class,
                  f.first()))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from handover_log where reservation_id=?",
                  Integer.class,
                  f.first()))
          .isEqualTo(1);
    } finally {
      jdbc.execute("DROP TRIGGER " + trigger + " ON reservation");
      jdbc.execute("DROP FUNCTION " + function + "()");
    }
    cleanup.clean();
    assertThat(archived(f)).isEqualTo(1);
  }

  @Test
  void activeCounterpartyRequestAndRemainingTransactionSurviveEarlierPurge() {
    var f = fixture();
    long active =
        jdbc.queryForObject(
            "insert into member(email_normalized,password_hash,state,created_at,verified_at) values(?,?,'ACTIVE',statement_timestamp(),statement_timestamp()) returning id",
            Long.class,
            UUID.randomUUID() + "@example.invalid",
            UUID.randomUUID().toString());
    long organization =
        jdbc.queryForObject(
            "insert into organization(name,primary_member_id) values('Synthetic active counterparty',?) returning id",
            Long.class,
            active);
    jdbc.update(
        "update pickup_request set organization_id=?,purpose='Synthetic retained input',transcript='Synthetic retained input' where id=?",
        organization,
        f.request());
    jdbc.update(
        "update reservation set organization_id=? where request_id=?", organization, f.request());
    jdbc.update(
        "update member set state='WITHDRAWN',email_normalized=null,password_hash=null,withdrawn_at=statement_timestamp()-interval '721 hours',purge_after=statement_timestamp()-interval '1 hour' where id=?",
        f.giver());
    cleanup.clean();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reservation where id=?", Integer.class, f.first()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reservation where id=?", Integer.class, f.second()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select purpose is not null and transcript is not null from pickup_request where id=?",
                Boolean.class,
                f.request()))
        .isTrue();
    assertThat(jdbc.queryForObject("select state from member where id=?", String.class, active))
        .isEqualTo("ACTIVE");
    assertThat(jdbc.queryForObject("select count(*) from item where id=?", Integer.class, f.item()))
        .isEqualTo(1);
  }

  @Test
  void deadlineAtTransactionTimeIsEligibleAndFutureDeadlineRemains() {
    var f = fixture();
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              jdbc.update(
                  "update reservation set retention_until=transaction_timestamp() where id=?",
                  f.first());
              repository.cleanup();
            });
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reservation where id=?", Integer.class, f.first()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reservation where id=?", Integer.class, f.second()))
        .isEqualTo(1);
  }

  @Test
  void overdueMonitoringIncludesMembersWithoutTransactionHistory() {
    long before = cleanup.overdueCount();
    long member =
        jdbc.queryForObject(
            "insert into member(state,created_at,withdrawn_at,purge_after) values('WITHDRAWN',statement_timestamp()-interval '800 hours',statement_timestamp()-interval '722 hours',statement_timestamp()-interval '2 hours') returning id",
            Long.class);
    assertThat(cleanup.overdueCount()).isEqualTo(before + 1);
    cleanup.clean();
    assertThat(jdbc.queryForObject("select count(*) from member where id=?", Integer.class, member))
        .isZero();
  }

  @Test
  void processLocalOwnerIsForgottenOnlyAfterPhysicalPurgeCommits() {
    long member =
        jdbc.queryForObject(
            "insert into member(state,created_at,withdrawn_at,purge_after) values('WITHDRAWN',statement_timestamp()-interval '800 hours',statement_timestamp()-interval '722 hours',statement_timestamp()-interval '2 hours') returning id",
            Long.class);
    try (var guard = work.erase(member)) {
      guard.commit();
    }
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              cleanup.clean();
              status.setRollbackOnly();
            });
    var ran = new java.util.concurrent.atomic.AtomicBoolean();
    work.register(member, new Object()).run(ignored -> ran.set(true));
    assertThat(ran).isFalse();
    assertThat(jdbc.queryForObject("select count(*) from member where id=?", Integer.class, member))
        .isEqualTo(1);
    cleanup.clean();
    assertThat(jdbc.queryForObject("select count(*) from member where id=?", Integer.class, member))
        .isZero();
    work.register(member, new Object()).run(ignored -> ran.set(true));
    assertThat(ran).isTrue();
  }

  private int archived(Fixture f) {
    return jdbc.queryForObject(
        "select archived_handed_over_qty from item where id=?", Integer.class, f.item());
  }

  private BigInteger volume(Fixture f) {
    return statistics.quantities(f.event()).stream()
        .map(ReuseStatsRepository.Quantity::cubicCm)
        .reduce(BigInteger.ZERO, BigInteger::add);
  }

  private Fixture fixture() {
    long giver =
        jdbc.queryForObject(
            "insert into member(email_normalized,password_hash,state,created_at,verified_at) values(?,?,'ACTIVE',statement_timestamp(),statement_timestamp()) returning id",
            Long.class,
            UUID.randomUUID() + "@example.invalid",
            UUID.randomUUID().toString());
    long receiver =
        jdbc.queryForObject(
            "insert into member(state,created_at,withdrawn_at,purge_after) values('WITHDRAWN',statement_timestamp()-interval '800 hours',statement_timestamp()-interval '721 hours',statement_timestamp()-interval '1 hour') returning id",
            Long.class);
    long giverOrg =
        jdbc.queryForObject(
            "insert into organization(name,primary_member_id) values('Synthetic giver',?) returning id",
            Long.class,
            giver);
    long receiverOrg =
        jdbc.queryForObject(
            "insert into organization(name,primary_member_id) values('Withdrawn organization',?) returning id",
            Long.class,
            receiver);
    long event =
        jdbc.queryForObject(
            "insert into event(organization_id,name,venue,teardown_deadline) values(?,'Synthetic event','Synthetic venue',clock_timestamp()+interval '1 day') returning id",
            Long.class,
            giverOrg);
    long item =
        jdbc.queryForObject(
            "insert into item(event_id,organization_id,category,label,width_cm,depth_cm,height_cm,condition,total_qty,available_qty,pickup_deadline) values(?,?,'STORAGE_BIN','Synthetic label',10,10,6,'GOOD',2,0,clock_timestamp()+interval '1 day') returning id",
            Long.class,
            event,
            giverOrg);
    long request =
        jdbc.queryForObject(
            "insert into pickup_request(organization_id,category,quantity,vehicle_type,created_at) values(?,'STORAGE_BIN',2,'VAN',clock_timestamp()) returning id",
            Long.class,
            receiverOrg);
    long first = reservation(request, item, receiverOrg, true);
    long second = reservation(request, item, receiverOrg, false);
    jdbc.update(
        "insert into reservation_transition(reservation_id,from_status,to_status,actor,at) values(?,'CONFIRMED','HANDED_OVER','HANDOVER',clock_timestamp())",
        first);
    jdbc.update(
        "insert into handover_log(reservation_id,expected_qty,counted_qty,accepted_qty,status,boxes,created_at) values(?,1,1,1,'COMPLETED','[]',clock_timestamp())",
        first);
    return new Fixture(giver, receiver, event, item, request, first, second);
  }

  private long reservation(long request, long item, long org, boolean expired) {
    return jdbc.queryForObject(
        "insert into reservation(request_id,item_id,organization_id,quantity,handed_over_qty,status,hold_expires_at,pickup_deadline,created_at,retention_until) values(?,?,?,1,1,'HANDED_OVER',clock_timestamp(),clock_timestamp()+interval '1 day',clock_timestamp(),clock_timestamp()+?::interval) returning id",
        Long.class,
        request,
        item,
        org,
        expired ? "-1 hour" : "1 hour");
  }

  private record Fixture(
      long giver, long receiver, long event, long item, long request, long first, long second) {}
}
