package com.afterbooth.global.idempotency;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.error.ErrorResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class IdempotencyService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final JsonMapper json;

  public IdempotencyService(
      JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock, JsonMapper json) {
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(manager);
    this.clock = clock;
    this.json = json;
  }

  public ResponseEntity<String> execute(
      UUID key, String method, String path, byte[] body, int successStatus, Supplier<?> action) {
    return executePrepared(key, method, path, body, successStatus, () -> action);
  }

  public ResponseEntity<String> executePrepared(
      UUID key,
      String method,
      String path,
      byte[] body,
      int successStatus,
      Supplier<? extends Supplier<?>> preparation) {
    String fingerprint = fingerprint(method, path, body);
    Stored existing =
        transaction.execute(
            status -> {
              Boolean locked =
                  jdbc.queryForObject(
                      "select pg_try_advisory_xact_lock(?)",
                      Boolean.class,
                      key.getMostSignificantBits() ^ key.getLeastSignificantBits());
              if (!Boolean.TRUE.equals(locked))
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
              List<Stored> rows =
                  jdbc.query(
                      "select fingerprint,state,response_status,response_body::text from idempotency_record where idempotency_key=?",
                      (r, n) ->
                          new Stored(
                              r.getString(1),
                              r.getString(2),
                              r.getObject(3, Integer.class),
                              r.getString(4)),
                      key);
              if (!rows.isEmpty()) return rows.getFirst();
              jdbc.update(
                  "insert into idempotency_record(idempotency_key,fingerprint,state,created_at) values (?,?,'IN_PROGRESS',?)",
                  key,
                  fingerprint,
                  java.sql.Timestamp.from(clock.instant()));
              return null;
            });
    if (existing != null) {
      if (!fingerprint.equals(existing.fingerprint()) || !existing.state().equals("DONE"))
        throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
      return response(existing.status(), existing.body());
    }
    try {
      Supplier<?> action = preparation.get();
      return transaction.execute(
          status -> {
            Object result = action.get();
            int resultStatus = successStatus;
            if (result instanceof Outcome outcome) {
              resultStatus = outcome.status();
              result = outcome.body();
            }
            String serialized = json.writeValueAsString(result);
            complete(key, resultStatus, serialized);
            return response(resultStatus, serialized);
          });
    } catch (BusinessException error) {
      String serialized =
          json.writeValueAsString(new ErrorResponse(error.errorCode().name(), error.getMessage()));
      return transaction.execute(
          status -> {
            complete(key, error.errorCode().status().value(), serialized);
            return response(error.errorCode().status().value(), serialized);
          });
    } catch (RuntimeException error) {
      transaction.executeWithoutResult(
          status ->
              jdbc.update(
                  "delete from idempotency_record where idempotency_key=? and state='IN_PROGRESS'",
                  key));
      throw error;
    }
  }

  public static String fingerprint(String method, String path, byte[] body) {
    try {
      return method
          + " "
          + path
          + " "
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private void complete(UUID key, int status, String body) {
    jdbc.update(
        "update idempotency_record set state='DONE',response_status=?,response_body=cast(? as jsonb) where idempotency_key=?",
        status,
        body,
        key);
  }

  private ResponseEntity<String> response(int status, String body) {
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
  }

  public record Outcome(int status, Object body) {}

  private record Stored(String fingerprint, String state, Integer status, String body) {}
}
