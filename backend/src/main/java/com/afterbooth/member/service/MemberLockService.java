package com.afterbooth.member.service;

import com.afterbooth.member.domain.Member;
import com.afterbooth.member.repository.MemberRepository;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberLockService {
  private final MemberRepository members;

  public MemberLockService(MemberRepository members) {
    this.members = members;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Member lock(long id) {
    return members.findByIdForUpdate(id);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Member lockByEmail(String email) {
    return members.findForUpdate(email);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public List<Member> lockAll(Collection<Long> ids) {
    return ids.stream().distinct().sorted().map(this::lock).toList();
  }
}
