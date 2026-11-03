package com.afterbooth.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.afterbooth.ai.*;
import com.afterbooth.global.error.BusinessException;
import com.afterbooth.inventory.domain.ItemCategory;
import com.afterbooth.inventory.domain.VehicleType;
import com.afterbooth.request.domain.ConditionContext;
import com.afterbooth.request.dto.ConditionInterpretation;
import com.afterbooth.request.repository.RequestReferences;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class ConditionServiceTest {
  private final AiGateway gateway = mock(AiGateway.class);
  private final RequestReferences references = mock(RequestReferences.class);
  private final ConditionService service =
      new ConditionService(
          gateway,
          references,
          Clock.fixed(Instant.parse("2026-10-31T16:00:00Z"), ZoneOffset.UTC),
          "minimal",
          "",
          org.mockito.Mockito.mock(com.afterbooth.global.security.DomainAccess.class));

  static ChatResponse answer(String json) {
    return new ChatResponse(json, List.of(), "stop", null);
  }

  @Test
  void interpretsTextAndOnlyReportsRequiredMissingFields() {
    when(gateway.chat(any())).thenReturn(answer("{\"purpose\":\"주말 행사\"}"));
    ConditionInterpretation result =
        service.interpret(ConditionContext.RECEIVER, null, null, null, "주말 행사");
    assertThat(result.transcript()).isEqualTo("주말 행사");
    assertThat(result.conditions().quantity()).isNull();
    assertThat(result.missingFields()).containsExactly("category", "vehicleType");
    verify(gateway, never()).transcribe(any());
  }

  @ParameterizedTest
  @EnumSource(ConditionContext.class)
  void blankTranscriptionReturnsEmptyConditionsWithoutSpark(ConditionContext context) {
    byte[] audio = wav();
    when(gateway.transcribe(audio)).thenReturn(new Transcription("  \t\n", 1000));
    when(references.eventDeadline(3L))
        .thenReturn(Optional.of(Instant.parse("2026-11-05T11:00:00Z")));
    var result = service.interpret(context, 3L, audio, "audio/wav", null);
    assertThat(result.transcript()).isEmpty();
    assertThat(result.conditions())
        .isEqualTo(new com.afterbooth.request.dto.Conditions(null, null, null, null, null));
    assertThat(result.missingFields())
        .containsExactlyElementsOf(
            context == ConditionContext.RECEIVER ? List.of("category", "vehicleType") : List.of());
    verify(gateway, never()).chat(any());
  }

  @Test
  void pinnedReferenceDateMakesReceiverPromptsIndependentOfClockTime() {
    when(gateway.chat(any())).thenReturn(answer("{\"purpose\":\"\"}"));
    var early =
        new ConditionService(
            gateway,
            references,
            Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC),
            "minimal",
            "2026-10-31",
            org.mockito.Mockito.mock(com.afterbooth.global.security.DomainAccess.class));
    var late =
        new ConditionService(
            gateway,
            references,
            Clock.fixed(Instant.parse("2026-11-02T23:00:00Z"), ZoneOffset.UTC),
            "minimal",
            "2026-10-31",
            org.mockito.Mockito.mock(com.afterbooth.global.security.DomainAccess.class));
    early.interpret(ConditionContext.RECEIVER, null, null, null, "today");
    late.interpret(ConditionContext.RECEIVER, null, null, null, "today");
    ArgumentCaptor<ChatRequest> calls = ArgumentCaptor.forClass(ChatRequest.class);
    verify(gateway, times(2)).chat(calls.capture());
    assertThat(calls.getAllValues().get(0)).isEqualTo(calls.getAllValues().get(1));
    assertThat(((ChatRequest.UserMessage) calls.getValue().messages().get(1)).text())
        .contains("\"referenceDate\":\"2026-10-31\"")
        .doesNotContain("currentTime");
  }

  @Test
  void transcribesAudioBeforeStructuring() {
    byte[] wav = wav();
    when(gateway.transcribe(wav)).thenReturn(new Transcription("주말 행사용 진열대 필요하고 승용차만 있어요", 1000));
    when(gateway.chat(any()))
        .thenReturn(
            answer(
                "{\"category\":\"DISPLAY_RACK\",\"vehicleType\":\"SEDAN\",\"purpose\":\"주말 행사"
                    + " 진열용\"}"));
    ConditionInterpretation result =
        service.interpret(ConditionContext.RECEIVER, null, wav, "audio/wav", null);
    assertThat(result.conditions().category()).isEqualTo(ItemCategory.DISPLAY_RACK);
    assertThat(result.conditions().vehicleType()).isEqualTo(VehicleType.SEDAN);
    assertThat(result.conditions().purpose()).isNotBlank();
    assertThat(result.missingFields()).isEmpty();
    var order = inOrder(gateway);
    order.verify(gateway).transcribe(wav);
    order.verify(gateway).chat(any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "{",
        "[]",
        "{}",
        "{\"quantity\":0,\"purpose\":\"\"}",
        "{\"quantity\":1.5,\"purpose\":\"\"}",
        "{\"category\":\"UNKNOWN\",\"purpose\":\"\"}",
        "{\"vehicleType\":\"BIKE\",\"purpose\":\"\"}",
        "{\"pickupDeadline\":\"tonight\",\"purpose\":\"\"}",
        "{\"purpose\":null}",
        "{\"organizationId\":1,\"purpose\":\"\"}"
      })
  void reasksOnceForInvalidOutputThenReturnsClearError(String invalid) {
    when(gateway.chat(any())).thenReturn(answer(invalid));
    assertThatThrownBy(() -> service.interpret(ConditionContext.RECEIVER, null, null, null, "조건"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.errorCode().name()).isEqualTo("AI_UNAVAILABLE"));
    verify(gateway, times(2)).chat(any());
  }

  @Test
  void acceptsCorrectedOutputOnSecondAttempt() {
    when(gateway.chat(any()))
        .thenReturn(
            answer("{\"quantity\":0,\"purpose\":\"\"}"),
            answer("{\"quantity\":2,\"purpose\":\"\"}"));
    assertThat(
            service
                .interpret(ConditionContext.RECEIVER, null, null, null, "두 개")
                .conditions()
                .quantity())
        .isEqualTo(2);
    ArgumentCaptor<ChatRequest> calls = ArgumentCaptor.forClass(ChatRequest.class);
    verify(gateway, times(2)).chat(calls.capture());
    assertThat(calls.getAllValues().get(1).messages()).hasSize(4);
    assertThat(calls.getValue().responseSchema().name()).isEqualTo("pickup_conditions");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "  \\t\\n"})
  void acceptsBlankPurposeAsAbsentWithoutReasking(String purpose) {
    when(gateway.chat(any())).thenReturn(answer("{\"purpose\":\"" + purpose + "\"}"));
    assertThat(
            service
                .interpret(ConditionContext.RECEIVER, null, null, null, "수납함 필요해요")
                .conditions()
                .purpose())
        .isNull();
    verify(gateway).chat(any());
  }

  @Test
  void reasksOnceWhenPurposeKeyIsMissing() {
    when(gateway.chat(any()))
        .thenReturn(answer("{\"quantity\":3}"), answer("{\"quantity\":3,\"purpose\":\"주말 행사\"}"));
    var result = service.interpret(ConditionContext.RECEIVER, null, null, null, "주말 행사에 세 개");
    assertThat(result.conditions().quantity()).isEqualTo(3);
    assertThat(result.conditions().purpose()).isEqualTo("주말 행사");
    verify(gateway, times(2)).chat(any());
  }

  @Test
  void usesSeoulCurrentDateAndNormalizesDeadlineOffset() {
    when(gateway.chat(any()))
        .thenReturn(answer("{\"pickupDeadline\":\"2026-11-01T11:00:00Z\",\"purpose\":\"\"}"));
    assertThat(
            service
                .interpret(ConditionContext.RECEIVER, null, null, null, "오늘 저녁 8시")
                .conditions()
                .pickupDeadline()
                .toString())
        .isEqualTo("2026-11-01T20:00+09:00");
    ArgumentCaptor<ChatRequest> calls = ArgumentCaptor.forClass(ChatRequest.class);
    verify(gateway).chat(calls.capture());
    assertThat(((ChatRequest.UserMessage) calls.getValue().messages().get(1)).text())
        .contains("\"referenceDate\":\"2026-11-01\"");
  }

  @Test
  void giverUsesEventDateRatherThanCurrentDate() {
    when(references.eventDeadline(3L))
        .thenReturn(Optional.of(Instant.parse("2026-11-05T11:00:00Z")));
    when(gateway.chat(any())).thenReturn(answer("{\"purpose\":\"\"}"));
    assertThat(service.interpret(ConditionContext.GIVER, 3L, null, null, "오늘 저녁").missingFields())
        .isEmpty();
    ArgumentCaptor<ChatRequest> calls = ArgumentCaptor.forClass(ChatRequest.class);
    verify(gateway).chat(calls.capture());
    assertThat(((ChatRequest.UserMessage) calls.getValue().messages().get(1)).text())
        .contains("\"referenceDate\":\"2026-11-05\"");
  }

  @Test
  void rejectsMissingGiverEventBeforeCallingAi() {
    assertThatThrownBy(() -> service.interpret(ConditionContext.GIVER, null, null, null, "오늘"))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(gateway);
  }

  @Test
  void rejectsUnknownGiverEventAsInvalidInput() {
    when(references.eventDeadline(99L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.interpret(ConditionContext.GIVER, 99L, null, null, "오늘"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.errorCode().status().value()).isEqualTo(400));
    verifyNoInteractions(gateway);
  }

  @Test
  void rejectsBothNeitherBlankAndTooLongInputBeforeAi() {
    assertThatThrownBy(
            () -> service.interpret(ConditionContext.RECEIVER, null, wav(), "audio/wav", "text"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.interpret(ConditionContext.RECEIVER, null, null, null, null))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.interpret(ConditionContext.RECEIVER, null, null, null, " "))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> service.interpret(ConditionContext.RECEIVER, null, null, null, "x".repeat(501)))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(gateway);
  }

  @Test
  void gatewayFailureDoesNotRetryAndMapsToUnavailable() {
    when(gateway.chat(any())).thenThrow(new AiCallException(Capability.SPARK, "timeout"));
    assertThatThrownBy(() -> service.interpret(ConditionContext.RECEIVER, null, null, null, "진열대"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.errorCode().status().value()).isEqualTo(503));
    verify(gateway).chat(any());
  }

  private static byte[] wav() {
    ByteBuffer out = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
    out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(38);
    out.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) 1);
    out.putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16);
    out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(2).putShort((short) 0);
    return out.array();
  }
}
