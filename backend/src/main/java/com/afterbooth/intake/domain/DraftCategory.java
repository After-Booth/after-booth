package com.afterbooth.intake.domain;

public enum DraftCategory {
  DISPLAY_RACK("Display rack"),
  STORAGE_BIN("Storage bin"),
  FOLDING_TABLE("Folding table"),
  UNKNOWN("Unclassified");
  private final String label;

  DraftCategory(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
