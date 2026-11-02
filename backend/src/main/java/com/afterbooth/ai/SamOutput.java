package com.afterbooth.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses SAM 3.1's special-token text. The whole text must be either empty (nothing found) or
 * {@code <0f>OBJECT(,OBJECT)*} followed by optional trailing whitespace, where OBJECT is {@code
 * n<|box;x1=..;y1=..;x2=..;y2=..;w=..;h=..;c=..|><|mask;x=..;y=..;c=..;data=..|>} (grammar from
 * dev.meta.ai/docs; every spike and replay SAM response has exactly this shape).
 */
final class SamOutput {

  private static final String PREFIX = "<0f>";
  private static final Pattern OBJECT =
      Pattern.compile("(\\d+)<\\|box;([^|]*)\\|><\\|mask;([^|]*?)data=([^|]*)\\|>");

  private SamOutput() {}

  static List<SamSegment> parse(String text) {
    if (text.isEmpty()) {
      return List.of();
    }
    if (!text.startsWith(PREFIX)) {
      throw malformed(text, 0);
    }
    List<SamSegment> segments = new ArrayList<>();
    Matcher matcher = OBJECT.matcher(text);
    int position = PREFIX.length();
    while (true) {
      matcher.region(position, text.length());
      if (!matcher.lookingAt()) {
        throw malformed(text, position);
      }
      segments.add(segment(matcher));
      position = matcher.end();
      if (!text.startsWith(",", position)) {
        break;
      }
      position++;
    }
    if (!text.substring(position).isBlank()) {
      throw malformed(text, position);
    }
    return segments;
  }

  private static SamSegment segment(Matcher matcher) {
    Map<String, String> box = fields(matcher.group(2));
    Map<String, String> mask = fields(matcher.group(3));
    return new SamSegment(
        number(matcher.group(1), "id", Integer::parseInt),
        new PixelBox(
            integer(box, "x1"), integer(box, "y1"), integer(box, "x2"), integer(box, "y2")),
        number(field(box, "c"), "c", Double::parseDouble),
        new SamSegment.Mask(integer(mask, "x"), integer(mask, "y"), matcher.group(4)));
  }

  private static AiCallException malformed(String text, int position) {
    String rest = text.substring(position);
    return new AiCallException(
        Capability.SAM,
        "output is not SAM segmentation text at character "
            + position
            + ": "
            + (rest.length() > 40 ? rest.substring(0, 40) + "..." : rest));
  }

  private static Map<String, String> fields(String group) {
    return Arrays.stream(group.split(";"))
        .filter(pair -> pair.contains("="))
        .map(pair -> pair.split("=", 2))
        .collect(
            Collectors.toMap(
                pair -> pair[0],
                pair -> pair[1],
                (first, second) -> {
                  throw new AiCallException(
                      Capability.SAM, "an object in the output repeats a field: " + group);
                }));
  }

  private static int integer(Map<String, String> fields, String name) {
    return number(field(fields, name), name, Integer::parseInt);
  }

  private static <T> T number(String value, String name, Function<String, T> parse) {
    try {
      return parse.apply(value);
    } catch (NumberFormatException e) {
      throw new AiCallException(
          Capability.SAM,
          null,
          "an object in the output has a non-numeric " + name + ": " + value,
          e);
    }
  }

  private static String field(Map<String, String> fields, String name) {
    String value = fields.get(name);
    if (value == null) {
      throw new AiCallException(Capability.SAM, "an object in the output has no " + name);
    }
    return value;
  }
}
