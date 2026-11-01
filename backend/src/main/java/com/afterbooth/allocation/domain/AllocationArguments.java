package com.afterbooth.allocation.domain;

import com.afterbooth.inventory.domain.ItemCategory;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public record AllocationArguments(
    Long itemId, int quantity, ItemCategory category, Map<String, Object> values) {
  public static AllocationArguments parse(JsonMapper json, String name, String raw) {
    if (name == null) throw new AgentProtocolViolation("Missing allocation tool name");
    Set<String> allowed =
        switch (name) {
          case "searchCandidates" -> Set.of("category");
          case "checkVehicleFit" -> Set.of("itemId");
          case "holdQuantity" -> Set.of("itemId", "quantity");
          default -> throw new AgentProtocolViolation("Unknown allocation tool");
        };
    if (raw == null || raw.length() > 4096) invalid();
    JsonNode node;
    try {
      node = json.readTree(raw);
    } catch (tools.jackson.core.JacksonException error) {
      throw new AgentProtocolViolation("Invalid allocation tool JSON");
    }
    if (node == null
        || !node.isObject()
        || (!name.equals("searchCandidates") && node.size() != allowed.size())) invalid();
    for (String field : node.propertyNames()) if (!allowed.contains(field)) invalid();
    if (name.equals("searchCandidates")) {
      if (!node.has("category")) return new AllocationArguments(null, 0, null, Map.of());
      JsonNode category = node.get("category");
      if (!category.isString()) invalid();
      try {
        ItemCategory parsed = ItemCategory.valueOf(category.asText());
        return new AllocationArguments(null, 0, parsed, Map.of("category", parsed.name()));
      } catch (IllegalArgumentException error) {
        throw new AgentProtocolViolation("Invalid search category");
      }
    }
    JsonNode id = node.get("itemId");
    if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0)
      invalid();
    if (name.equals("checkVehicleFit"))
      return new AllocationArguments(id.longValue(), 0, null, Map.of("itemId", id.longValue()));
    JsonNode qty = node.get("quantity");
    if (qty == null || !qty.isIntegralNumber() || !qty.canConvertToInt() || qty.intValue() <= 0)
      invalid();
    return new AllocationArguments(
        id.longValue(),
        qty.intValue(),
        null,
        Map.of("itemId", id.longValue(), "quantity", qty.intValue()));
  }

  private static void invalid() {
    throw new AgentProtocolViolation("Invalid allocation tool arguments");
  }
}
