package com.afterbooth.inventory.service;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.DomainAccess;
import com.afterbooth.inventory.domain.Condition;
import com.afterbooth.inventory.domain.Dimensions;
import com.afterbooth.inventory.domain.Event;
import com.afterbooth.inventory.domain.Item;
import com.afterbooth.inventory.domain.ItemCategory;
import com.afterbooth.inventory.domain.VehicleType;
import com.afterbooth.inventory.dto.ItemResponse;
import com.afterbooth.inventory.repository.EventRepository;
import com.afterbooth.inventory.repository.ItemRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ItemService {
  private final ItemRepository items;
  private final EventRepository events;
  private final DomainAccess access;

  public ItemService(ItemRepository items, EventRepository events, DomainAccess access) {
    this.items = items;
    this.events = events;
    this.access = access;
  }

  @Transactional(readOnly = true)
  public void requireEvent(Long id) {
    access.event(id);
    findEvent(id);
  }

  @Transactional
  public List<ItemResponse> register(
      Long eventId,
      Long draftId,
      List<Registration> lines,
      Instant deadline,
      VehicleType vehicle,
      String transcript) {
    long organizationId = access.event(eventId);
    access.lockOrganizations(List.of(organizationId));
    Event event = findEvent(eventId);
    Instant effective = event.intakePickupDeadline(deadline);
    return lines.stream()
        .map(
            line ->
                ItemResponse.from(
                    items.save(
                        Item.register(
                            eventId,
                            event.organizationId(),
                            draftId,
                            line.category(),
                            line.size(),
                            line.condition(),
                            line.quantity(),
                            effective,
                            vehicle,
                            transcript))))
        .toList();
  }

  @Transactional(readOnly = true)
  public ItemResponse get(Long id) {
    access.actor();
    Item item = items.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (!access.activeOrganization(item.organizationId()))
      throw new BusinessException(ErrorCode.NOT_FOUND);
    return ItemResponse.from(item, access.owner(item.organizationId()));
  }

  @Transactional(readOnly = true)
  public List<Item> candidates() {
    access.actor();
    return items.findAll(org.springframework.data.domain.Sort.by("id")).stream()
        .filter(i -> access.activeOrganization(i.organizationId()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<ItemResponse> list(Long eventId, boolean availableOnly, Instant now) {
    return candidates().stream()
        .filter(i -> eventId == null || eventId.equals(i.eventId()))
        .filter(i -> !availableOnly || (i.availableQty() > 0 && !i.isPickupClosed(now)))
        .map(i -> ItemResponse.from(i, access.owner(i.organizationId())))
        .toList();
  }

  private Event findEvent(Long id) {
    return events.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  public record Registration(
      ItemCategory category, int quantity, Dimensions size, Condition condition) {}
}
