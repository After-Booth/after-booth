package com.afterbooth.intake.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;

class StrictIdDeserializer extends ValueDeserializer<Long> {
  @Override
  public Long deserialize(JsonParser parser, DeserializationContext context) {
    if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) return parser.getLongValue();
    throw MismatchedInputException.from(parser, Long.class, "Expected a JSON integer");
  }
}
