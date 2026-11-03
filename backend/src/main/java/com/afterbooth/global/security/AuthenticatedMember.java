package com.afterbooth.global.security;

import java.util.UUID;

public record AuthenticatedMember(long memberId, UUID sessionId, long authVersion) {}
