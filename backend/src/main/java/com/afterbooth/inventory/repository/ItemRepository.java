package com.afterbooth.inventory.repository;

import com.afterbooth.inventory.domain.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, Long> {
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update Item i set i.availableQty=i.availableQty-:q where i.id=:id and :q>0 and i.availableQty>=:q")
  int decrease(@Param("id") Long id, @Param("q") int q);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update Item i set i.availableQty=i.availableQty+:q where i.id=:id and :q>0 and i.availableQty+:q<=i.totalQty")
  int restore(@Param("id") Long id, @Param("q") int q);
}
