package com.afterbooth.inventory.service;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.inventory.domain.Item;
import com.afterbooth.inventory.repository.ItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ItemStockService {
  private final ItemRepository repository;

  public ItemStockService(ItemRepository repository) {
    this.repository = repository;
  }

  public Item get(Long id) {
    return repository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BusinessException.class)
  public void decrease(Long id, int quantity) {
    if (repository.decrease(id, quantity) != 1)
      throw new BusinessException(ErrorCode.QTY_UNAVAILABLE);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void restore(Long id, int quantity) {
    if (repository.restore(id, quantity) != 1)
      throw new IllegalStateException("Stock invariant violated");
  }
}
