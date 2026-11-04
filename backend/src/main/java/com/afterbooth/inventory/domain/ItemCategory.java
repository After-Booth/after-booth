package com.afterbooth.inventory.domain;

public enum ItemCategory {
  DISPLAY_RACK("Display rack"),
  STORAGE_BIN("Storage bin"),
  FOLDING_TABLE("Folding table");
  private final String label;

  ItemCategory(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
