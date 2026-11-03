package com.afterbooth.member.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;

class StrictPasswordDeserializer extends ValueDeserializer<String> {
  @Override
  public String deserialize(JsonParser parser, DeserializationContext context) {
    if (parser.currentToken() == JsonToken.VALUE_STRING) return parser.getString();
    throw MismatchedInputException.from(parser, String.class, "Expected a JSON string");
  }
}
