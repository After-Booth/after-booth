package com.afterbooth.ai;

import java.util.List;

/**
 * The single seam for every Meta Model API call (AGENTS.md hard rule 4). {@code AI_MODE} decides
 * whether a call goes to the network with a recorded fallback (live), reuses an existing fixture or
 * records one shared call per request key (record), or reads fixtures only (replay).
 *
 * <p>No call is retried here; callers own their re-ask logic. A failed call throws {@link
 * AiCallException} unless live fallback is available; a replay request without a recorded fixture
 * throws {@link MissingFixtureException}.
 */
public interface AiGateway {

  /** Muse Spark chat completion. Tool calls come back to the caller, which executes them. */
  ChatResponse chat(ChatRequest request);

  /** SAM 3.1 segmentation. An image with nothing found gives an empty list. */
  List<SamSegment> segment(SamRequest request);

  /** Muse Voice Transcribe on a 16 kHz mono 16-bit PCM WAV clip (D-030). */
  Transcription transcribe(byte[] wav);
}
