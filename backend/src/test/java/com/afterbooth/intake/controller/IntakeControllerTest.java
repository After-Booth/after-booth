package com.afterbooth.intake.controller;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.error.ErrorResponse;
import com.afterbooth.global.idempotency.IdempotencyService;
import com.afterbooth.intake.domain.IntakeStage;
import com.afterbooth.intake.domain.IntakeStatus;
import com.afterbooth.intake.dto.ConfirmIntakeRequest;
import com.afterbooth.intake.dto.IntakeDraftResponse;
import com.afterbooth.intake.service.IntakeService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IntakeController.class)
class IntakeControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean IntakeService service;
  @MockitoBean IdempotencyService idempotency;

  @BeforeEach
  void executeActionAndSerializeResponse() {
    when(idempotency.execute(
            any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
        .thenAnswer(
            invocation -> {
              int status = invocation.getArgument(4);
              Supplier<?> action = invocation.getArgument(5);
              Object body;
              try {
                body = action.get();
              } catch (BusinessException error) {
                status = error.errorCode().status().value();
                body = new ErrorResponse(error.errorCode().name(), error.getMessage());
              }
              return ResponseEntity.status(status)
                  .contentType(MediaType.APPLICATION_JSON)
                  .body(json.writeValueAsString(body));
            });
  }

  @Test
  void createReturnsAcceptedProcessingSnapshot() throws Exception {
    when(service.create(eq(1L), any(), eq("image/png")))
        .thenReturn(
            new IntakeDraftResponse(
                2L,
                1L,
                IntakeStatus.PROCESSING,
                IntakeStage.ESTIMATE,
                null,
                List.of(),
                OffsetDateTime.parse("2026-10-31T12:00:00+09:00")));
    mvc.perform(
            multipart("/api/intake/drafts")
                .file(new MockMultipartFile("photo", "photo.png", "image/png", new byte[] {1}))
                .param("eventId", "1")
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("PROCESSING"))
        .andExpect(jsonPath("$.stage").value("ESTIMATE"))
        .andExpect(jsonPath("$.failureMessage").doesNotExist())
        .andExpect(jsonPath("$.createdAt").value("2026-10-31T12:00:00+09:00"));
    verify(idempotency)
        .execute(
            any(),
            eq("POST"),
            eq("/api/intake/drafts"),
            eq(
                ("eventId=1\nphoto=4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            eq(202),
            any());
  }

  @Test
  void confirmPassesRawRequestBytesToIdempotencyAndReturnsArray() throws Exception {
    UUID key = UUID.randomUUID();
    String body =
        """
        { "lines": [{"lineId":1,"category":"DISPLAY_RACK","quantity":1,
          "sizeCm":{"width":1,"depth":1,"height":1},"condition":"GOOD"}] }
        """;
    when(service.confirm(eq(1L), any(ConfirmIntakeRequest.class))).thenReturn(List.of());
    mvc.perform(
            post("/api/intake/drafts/1/confirm")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$").isArray());
    verify(idempotency)
        .execute(
            eq(key),
            eq("POST"),
            eq("/api/intake/drafts/1/confirm"),
            eq(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            eq(201),
            any());
  }

  @Test
  void confirmRejectsNonJsonContentType() throws Exception {
    mvc.perform(
            post("/api/intake/drafts/1/confirm")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.TEXT_PLAIN)
                .content("{}"))
        .andExpect(status().isUnsupportedMediaType());
    verifyNoInteractions(service);
  }

  @Test
  void missingPhotoReturnsInvalidInput() throws Exception {
    mvc.perform(
            multipart("/api/intake/drafts")
                .param("eventId", "1")
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    verifyNoInteractions(service);
  }

  @Test
  void missingAndMalformedKeyReturnInvalidInput() throws Exception {
    mvc.perform(
            multipart("/api/intake/drafts")
                .file(new MockMultipartFile("photo", new byte[] {1}))
                .param("eventId", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    mvc.perform(
            multipart("/api/intake/drafts")
                .file(new MockMultipartFile("photo", new byte[] {1}))
                .param("eventId", "1")
                .header("Idempotency-Key", "bad"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    verifyNoInteractions(service);
  }

  @Test
  void rejectsUnknownCategoryAndNonPositiveQuantityAndEmptyLines() throws Exception {
    for (String body :
        List.of(
            "{\"lines\":[]}",
            "{\"lines\":[{\"lineId\":1,\"category\":\"UNKNOWN\",\"quantity\":1,\"sizeCm\":{\"width\":1,\"depth\":1,\"height\":1},\"condition\":\"GOOD\"}]}",
            "{\"lines\":[{\"lineId\":1,\"category\":\"DISPLAY_RACK\",\"quantity\":0,\"sizeCm\":{\"width\":1,\"depth\":1,\"height\":1},\"condition\":\"GOOD\"}]}")) {
      mvc.perform(
              post("/api/intake/drafts/1/confirm")
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
    verifyNoInteractions(service);
  }

  @Test
  void confirmRejectsNonIntegerJsonBeforeService() throws Exception {
    String sizes = "\"sizeCm\":{\"width\":1,\"depth\":1,\"height\":1}";
    for (String body :
        List.of(
            "{\"lines\":[{\"lineId\":1.0,\"category\":\"DISPLAY_RACK\",\"quantity\":1,"
                + sizes
                + ",\"condition\":\"GOOD\"}]}",
            "{\"lines\":[{\"lineId\":1,\"category\":\"DISPLAY_RACK\",\"quantity\":1.5,"
                + sizes
                + ",\"condition\":\"GOOD\"}]}",
            "{\"lines\":[{\"lineId\":1,\"category\":\"DISPLAY_RACK\",\"quantity\":\"2\","
                + sizes
                + ",\"condition\":\"GOOD\"}]}",
            "{\"lines\":[{\"lineId\":1,\"category\":\"DISPLAY_RACK\",\"quantity\":1,\"sizeCm\":{\"width\":\"90\",\"depth\":1,\"height\":1},\"condition\":\"GOOD\"}]}",
            "{\"lines\":[{\"lineId\":1,\"category\":\"DISPLAY_RACK\",\"quantity\":1,\"sizeCm\":{\"width\":1,\"depth\":90.5,\"height\":1},\"condition\":\"GOOD\"}]}")) {
      mvc.perform(
              post("/api/intake/drafts/1/confirm")
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
    verifyNoInteractions(service);
  }

  @Test
  void mapsDraftConflictToContractError() throws Exception {
    when(service.get(1L)).thenThrow(new BusinessException(ErrorCode.DRAFT_NOT_READY));
    mvc.perform(get("/api/intake/drafts/1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DRAFT_NOT_READY"));
  }

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("handlerExceptionResolver")
  org.springframework.web.servlet.HandlerExceptionResolver resolver;

  @Test
  void multipartLimitMapsBeforeAHandlerIsSelected() throws Exception {
    var request =
        new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/intake/drafts");
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    resolver.resolveException(
        request,
        response,
        null,
        new org.springframework.web.multipart.MaxUploadSizeExceededException(10 * 1024 * 1024));
    org.assertj.core.api.Assertions.assertThat(response.getStatus()).isEqualTo(400);
    org.assertj.core.api.Assertions.assertThat(response.getContentAsString())
        .contains("INVALID_INPUT");
  }

  @Autowired tools.jackson.databind.json.JsonMapper json;

  @Test
  void persistedDraftResponseRetainsSeoulOffsetOnReplay() {
    var first =
        new IntakeDraftResponse(
            2L,
            1L,
            IntakeStatus.PROCESSING,
            IntakeStage.ESTIMATE,
            null,
            List.of(),
            OffsetDateTime.parse("2026-10-31T12:00:00+09:00"));
    var replay = json.readValue(json.writeValueAsString(first), IntakeDraftResponse.class);
    org.assertj.core.api.Assertions.assertThat(replay).isEqualTo(first);
    org.assertj.core.api.Assertions.assertThat(json.writeValueAsString(replay))
        .contains("2026-10-31T12:00:00+09:00");
  }
}
