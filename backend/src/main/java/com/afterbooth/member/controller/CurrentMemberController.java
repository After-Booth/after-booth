package com.afterbooth.member.controller;

import com.afterbooth.global.security.DomainAccess;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CurrentMemberController {
  private final DomainAccess access;

  CurrentMemberController(DomainAccess access) {
    this.access = access;
  }

  @GetMapping("/api/members/me")
  Map<String, Object> get() {
    return access.currentMember();
  }
}
