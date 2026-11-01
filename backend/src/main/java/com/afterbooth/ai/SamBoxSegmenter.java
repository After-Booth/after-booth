package com.afterbooth.ai;

import com.afterbooth.global.domain.BoundingBox;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Shared box batching and one-based SAM prompt correspondence for photo pipelines. */
public final class SamBoxSegmenter {
  private SamBoxSegmenter() {}

  public static Map<Integer, BoundingBox> segment(
      AiGateway gateway, Image image, List<BoundingBox> boxes, double samMinScore) {
    if (boxes.isEmpty()) return Map.of();
    BufferedImage decoded;
    try {
      decoded = ImageIO.read(new ByteArrayInputStream(image.bytes()));
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot decode photo", e);
    }
    if (decoded == null) throw new IllegalArgumentException("Cannot decode photo");
    int width = decoded.getWidth();
    int height = decoded.getHeight();
    Map<Integer, BoundingBox> segmented = new HashMap<>();
    for (int start = 0; start < boxes.size(); start += SamRequest.Boxes.MAX_BOXES) {
      List<PixelBox> prompts =
          boxes.subList(start, Math.min(start + SamRequest.Boxes.MAX_BOXES, boxes.size())).stream()
              .map(box -> pixels(box, width, height))
              .toList();
      List<SamSegment> output =
          gateway.segment(new SamRequest(image, new SamRequest.Boxes(width, height, prompts)));
      Map<Integer, Long> occurrences = new HashMap<>();
      output.forEach(segment -> occurrences.merge(segment.id(), 1L, Long::sum));
      for (SamSegment segment : output) {
        if (segment.id() < 1
            || segment.id() > prompts.size()
            || occurrences.get(segment.id()) != 1
            || !Double.isFinite(segment.score())
            || segment.score() < samMinScore
            || segment.score() > 1
            || segment.mask() == null
            || segment.mask().data() == null
            || segment.mask().data().isBlank()) continue;
        PixelBox box = segment.box();
        if (box == null
            || box.x1() < 0
            || box.y1() < 0
            || box.x2() < box.x1()
            || box.y2() < box.y1()
            || box.x2() >= width
            || box.y2() >= height) continue;
        segmented.put(
            start + segment.id() - 1,
            new BoundingBox(
                (double) box.x1() / width,
                (double) box.y1() / height,
                (double) (box.x2() - box.x1() + 1) / width,
                (double) (box.y2() - box.y1() + 1) / height));
      }
    }
    return Map.copyOf(segmented);
  }

  private static PixelBox pixels(BoundingBox box, int width, int height) {
    int x1 = Math.min(width - 1, (int) Math.floor(box.x() * width));
    int y1 = Math.min(height - 1, (int) Math.floor(box.y() * height));
    return new PixelBox(
        x1,
        y1,
        Math.max(x1, Math.min(width - 1, (int) Math.ceil((box.x() + box.width()) * width) - 1)),
        Math.max(y1, Math.min(height - 1, (int) Math.ceil((box.y() + box.height()) * height) - 1)));
  }
}
