package com.afterbooth.request.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.request.domain.ConditionContext;
import com.afterbooth.request.domain.ConditionOutput;
import com.afterbooth.request.dto.ConditionInterpretation;
import com.afterbooth.request.dto.Conditions;
import com.afterbooth.request.service.ConditionService;
import com.afterbooth.request.service.PickupRequestService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@org.springframework.boot.test.context.SpringBootTest(
    properties = "spring.docker.compose.enabled=false")
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(
    print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@org.springframework.context.annotation.Import({
  com.afterbooth.AuthenticatedMockMvcConfiguration.class,
  com.afterbooth.TestcontainersConfiguration.class
})
class RequestControllerTest {
  @Autowired MockMvc mvc;
  @Autowired org.springframework.core.env.Environment environment;
  @MockitoBean ConditionService conditions;
  @MockitoBean PickupRequestService requests;
  @MockitoBean com.afterbooth.global.idempotency.IdempotencyService idempotency;

  @Test
  void pickupCreationUsesSharedIdempotencyWithExactHttpBody() throws Exception {
    var key = java.util.UUID.randomUUID();
    byte[] body = "{\"organizationId\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(idempotency.execute(
            eq(key), eq("POST"), eq("/api/pickup-requests"), eq(body), eq(201), any()))
        .thenAnswer(
            call -> {
              ((java.util.function.Supplier<?>) call.getArgument(5)).get();
              return org.springframework.http.ResponseEntity.status(201).body("{\"id\":42}");
            });
    mvc.perform(
            post("/api/pickup-requests")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(42));
    org.mockito.Mockito.verify(requests)
        .create(new String(body, java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void missingContextAndIdempotencyKeyAreInvalidInput() throws Exception {
    mvc.perform(multipart("/api/conditions/interpret").param("text", "진열대"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    mvc.perform(post("/api/pickup-requests").contentType("application/json").content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @Test
  void invalidAudioUses415ContractError() throws Exception {
    when(conditions.interpret(any(), any(), any(), any(), any()))
        .thenThrow(new BusinessException(ErrorCode.UNSUPPORTED_REQUEST));
    mvc.perform(
            multipart("/api/conditions/interpret")
                .file(new MockMultipartFile("audio", "clip.ogg", "audio/ogg", new byte[] {1}))
                .param("context", "RECEIVER"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_REQUEST"));
  }

  @Test
  void exhaustedReaskUses503ContractError() throws Exception {
    when(conditions.interpret(any(), any(), any(), any(), any()))
        .thenThrow(new BusinessException(ErrorCode.AI_UNAVAILABLE));
    mvc.perform(
            multipart("/api/conditions/interpret")
                .param("context", "RECEIVER")
                .param("text", "진열대"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));
  }

  @Test
  void responseOmitsOptionalFieldsRatherThanReturningNull() throws Exception {
    when(conditions.interpret(any(), any(), any(), any(), any()))
        .thenReturn(
            new ConditionInterpretation(
                ConditionContext.RECEIVER,
                "행사",
                new Conditions(null, null, null, null, "행사"),
                List.of("category", "vehicleType")));
    mvc.perform(
            multipart("/api/conditions/interpret").param("context", "RECEIVER").param("text", "행사"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conditions.purpose").value("행사"))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("null"))));
  }

  @Test
  void emptyTranscriptReturns200AndEmptyConditions() throws Exception {
    when(conditions.interpret(any(), any(), any(), any(), any()))
        .thenReturn(
            new ConditionInterpretation(
                ConditionContext.RECEIVER,
                "",
                new Conditions(null, null, null, null, null),
                List.of("category", "vehicleType")));
    mvc.perform(
            multipart("/api/conditions/interpret")
                .file(new MockMultipartFile("audio", "clip.wav", "audio/wav", new byte[] {1}))
                .param("context", "RECEIVER"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    "{\"context\":\"RECEIVER\",\"transcript\":\"\",\"conditions\":{},\"missingFields\":[\"category\",\"vehicleType\"]}",
                    org.springframework.test.json.JsonCompareMode.STRICT));
  }

  @Test
  void configuredMultipartLimitsAllowSixtySecondWavWithFraming() {
    long fileLimit =
        org.springframework.util.unit.DataSize.parse(
                environment.getRequiredProperty("spring.servlet.multipart.max-file-size"))
            .toBytes();
    long requestLimit =
        org.springframework.util.unit.DataSize.parse(
                environment.getRequiredProperty("spring.servlet.multipart.max-request-size"))
            .toBytes();
    org.assertj.core.api.Assertions.assertThat(fileLimit).isGreaterThanOrEqualTo(1920044L);
    org.assertj.core.api.Assertions.assertThat(requestLimit).isGreaterThan(fileLimit);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "  \\t\\n"})
  void responseOmitsBlankPurpose(String purpose) throws Exception {
    when(conditions.interpret(any(), any(), any(), any(), any()))
        .thenReturn(
            new ConditionInterpretation(
                ConditionContext.RECEIVER,
                "수납함 필요해요",
                ConditionOutput.parse("{\"purpose\":\"" + purpose + "\"}"),
                List.of("category", "vehicleType")));
    mvc.perform(
            multipart("/api/conditions/interpret")
                .param("context", "RECEIVER")
                .param("text", "수납함 필요해요"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    "{\"context\":\"RECEIVER\",\"transcript\":\"수납함"
                        + " 필요해요\",\"conditions\":{},\"missingFields\":[\"category\",\"vehicleType\"]}",
                    org.springframework.test.json.JsonCompareMode.STRICT));
  }
}
