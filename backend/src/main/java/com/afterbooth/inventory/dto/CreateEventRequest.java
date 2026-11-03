package com.afterbooth.inventory.dto;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import java.time.OffsetDateTime;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

public record CreateEventRequest(String name, String venue, OffsetDateTime teardownDeadline) {
  public static CreateEventRequest parse(JsonMapper json, byte[] body) {
    try {
      var tree = json.readTree(body);
      var fields = Set.of("name", "venue", "teardownDeadline");
      if (tree == null || !tree.isObject() || tree.size() != fields.size()) invalid();
      for (var entry : tree.properties()) {
        if (!fields.contains(entry.getKey()) || !entry.getValue().isString()) invalid();
      }
      var result = json.readValue(body, CreateEventRequest.class);
      if (result.name() == null
          || result.name().isBlank()
          || result.name().length() > 100
          || result.venue() == null
          || result.venue().isBlank()
          || result.venue().length() > 200
          || result.teardownDeadline() == null) invalid();
      return result;
    } catch (RuntimeException error) {
      throw new BusinessException(ErrorCode.INVALID_INPUT);
    }
  }

  private static void invalid() {
    throw new BusinessException(ErrorCode.INVALID_INPUT);
  }
}
