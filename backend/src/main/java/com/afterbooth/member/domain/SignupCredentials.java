package com.afterbooth.member.domain;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import java.util.Locale;
import java.util.regex.Pattern;

public final class SignupCredentials {
  private static final Pattern ADDRESS =
      Pattern.compile(
          "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*");

  private SignupCredentials() {}

  public static String normalizeEmail(String input) {
    if (input == null) return invalid();
    int start = 0, end = input.length();
    while (start < end && input.charAt(start) == ' ') start++;
    while (end > start && input.charAt(end - 1) == ' ') end--;
    String email = input.substring(start, end);
    if (email.length() > 254 || email.indexOf('@') > 64 || !ADDRESS.matcher(email).matches())
      return invalid();
    return email.toLowerCase(Locale.ROOT);
  }

  public static String password(String password) {
    if (password == null) return invalid();
    int length = password.codePointCount(0, password.length());
    if (length < 12 || length > 128) return invalid();
    return password;
  }

  public static void challenge(String id, String code) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{43}") || code == null || !code.matches("[0-9]{6}"))
      invalid();
  }

  private static String invalid() {
    throw new BusinessException(ErrorCode.INVALID_INPUT);
  }
}
