package com.afterbooth.intake.service;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.AuthenticatedMember;
import com.afterbooth.global.security.DomainAccess;
import com.afterbooth.intake.domain.IntakeDraft;
import com.afterbooth.intake.domain.IntakeDraftLine;
import com.afterbooth.intake.pipeline.EstimatedLine;
import com.afterbooth.intake.repository.IntakeDraftLineRepository;
import com.afterbooth.intake.repository.IntakeDraftRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntakeProcessingStore {
  private final IntakeDraftRepository drafts;
  private final IntakeDraftLineRepository lines;
  private final DomainAccess access;

  public IntakeProcessingStore(
      IntakeDraftRepository drafts, IntakeDraftLineRepository lines, DomainAccess access) {
    this.drafts = drafts;
    this.lines = lines;
    this.access = access;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void segment(Long id, AuthenticatedMember actor) {
    authorize(id, actor);
    find(id).beginSegmentation();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void ready(Long id, List<EstimatedLine> estimates, AuthenticatedMember actor) {
    authorize(id, actor);
    IntakeDraft draft = find(id);
    estimates.forEach(
        e ->
            lines.save(
                IntakeDraftLine.identify(
                    id,
                    e.category(),
                    e.count(),
                    e.boxes(),
                    e.widthCm(),
                    e.depthCm(),
                    e.heightCm(),
                    e.condition(),
                    e.confidence(),
                    e.needsReview())));
    draft.ready();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void fail(Long id, String message, AuthenticatedMember actor) {
    authorize(id, actor);
    find(id).fail(message);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public void check(Long id, AuthenticatedMember actor) {
    access.draft(id, actor);
  }

  private void authorize(Long id, AuthenticatedMember actor) {
    long organizationId = access.draft(id, actor);
    access.lockOrganizations(List.of(organizationId), actor);
    access.draft(id, actor);
  }

  private IntakeDraft find(Long id) {
    return drafts.findLocked(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }
}
