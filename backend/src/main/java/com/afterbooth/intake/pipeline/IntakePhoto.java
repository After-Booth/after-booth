package com.afterbooth.intake.pipeline;

public record IntakePhoto(byte[] bytes, String mediaType) {
  public IntakePhoto {
    bytes = bytes.clone();
  }

  public void erase() {
    java.util.Arrays.fill(bytes, (byte) 0);
  }

  @Override
  public byte[] bytes() {
    return bytes.clone();
  }
}
