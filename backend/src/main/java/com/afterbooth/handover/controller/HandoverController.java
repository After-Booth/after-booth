package com.afterbooth.handover.controller;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.idempotency.IdempotencyService;
import com.afterbooth.handover.dto.ResolveHandoverRequest;
import com.afterbooth.handover.service.HandoverService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/reservations/{id}/handover")
class HandoverController {
  private final HandoverService service;
  private final IdempotencyService idempotency;
  private final JsonMapper json;
  private final Validator validator;

  HandoverController(
      HandoverService service,
      IdempotencyService idempotency,
      JsonMapper json,
      Validator validator) {
    this.service = service;
    this.idempotency = idempotency;
    this.json = json;
    this.validator = validator;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<String> submit(
      @PathVariable Long id,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestPart MultipartFile photo,
      HttpServletRequest http)
      throws IOException {
    byte[] bytes = photo.getBytes();
    byte[] fingerprint =
        IdempotencyService.fingerprint("photo", String.valueOf(photo.getContentType()), bytes)
            .getBytes(StandardCharsets.UTF_8);
    return idempotency.executePrepared(
        key,
        http.getMethod(),
        http.getRequestURI(),
        fingerprint,
        200,
        () -> service.prepare(id, bytes, photo.getContentType()));
  }

  @PostMapping(value = "/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> resolve(
      @PathVariable Long id,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody byte[] body,
      HttpServletRequest http) {
    return idempotency.execute(
        key,
        http.getMethod(),
        http.getRequestURI(),
        body,
        200,
        () -> {
          ResolveHandoverRequest request;
          try {
            request = json.readValue(body, ResolveHandoverRequest.class);
          } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
          }
          if (request == null || !validator.validate(request).isEmpty())
            throw new BusinessException(ErrorCode.INVALID_INPUT);
          return service.resolve(id, request.acceptedQty());
        });
  }
}
