package com.afterbooth.intake.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;

class StrictIntegerDeserializer extends ValueDeserializer<Integer> {
  @Override
  public Integer deserialize(JsonParser parser, DeserializationContext context) {
    if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) return parser.getIntValue();
    throw MismatchedInputException.from(parser, Integer.class, "Expected a JSON integer");
  }
}
