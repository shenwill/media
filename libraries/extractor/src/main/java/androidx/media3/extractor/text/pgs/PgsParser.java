/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.text.pgs;

import static java.lang.Math.min;

import android.graphics.Bitmap;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.Inflater;

/** A {@link SubtitleParser} for PGS subtitles. */
@UnstableApi
public final class PgsParser implements SubtitleParser {

  private static final int SECTION_TYPE_PALETTE = 0x14;
  private static final int SECTION_TYPE_BITMAP_PICTURE = 0x15;
  private static final int SECTION_TYPE_IDENTIFIER = 0x16;
  private static final int SECTION_TYPE_WINDOW_DEF = 0x17;
  private static final int SECTION_TYPE_END = 0x80;

  private static final int SEQUENCE_FIRST = 0x80;
  private static final int SEQUENCE_LAST = 0x40;

  private static final byte INFLATE_HEADER = 0x78;

  private final ParsableByteArray buffer;
  private final ParsableByteArray inflatedBuffer;
  private final CueBuilder cueBuilder;
  @Nullable private Inflater inflater;

  public PgsParser() {
    buffer = new ParsableByteArray();
    inflatedBuffer = new ParsableByteArray();
    cueBuilder = new CueBuilder();
  }

  @Override
  public void reset() {}

  @Override
  public ImmutableList<CuesWithTiming> parse(byte[] data, int offset, int length) {
    buffer.reset(data, /* limit= */ offset + length);
    buffer.setPosition(offset);
    maybeInflateData(buffer);
    cueBuilder.reset();
    ArrayList<Cue> cues = new ArrayList<>();
    while (buffer.bytesLeft() >= 3) {
      Cue cue = readNextSection(buffer, cueBuilder);
      if (cue != null) {
        cues.add(cue);
      }
    }
    return ImmutableList.of(
        new CuesWithTiming(cues, /* startTimeUs= */ C.TIME_UNSET, /* durationUs= */ C.TIME_UNSET));
  }

  private void maybeInflateData(ParsableByteArray buffer) {
    if (buffer.bytesLeft() > 0 && buffer.peekUnsignedByte() == INFLATE_HEADER) {
      if (inflater == null) {
        inflater = new Inflater();
      }
      if (Util.inflate(buffer, inflatedBuffer, inflater)) {
        buffer.reset(inflatedBuffer.getData(), inflatedBuffer.limit());
      } // else assume data is not compressed.
    }
  }

  @Nullable
  private static Cue readNextSection(ParsableByteArray buffer, CueBuilder cueBuilder) {
    int limit = buffer.limit();
    int sectionType = buffer.readUnsignedByte();
    int sectionLength = buffer.readUnsignedShort();

    int nextSectionPosition = buffer.getPosition() + sectionLength;
    if (nextSectionPosition > limit) {
      buffer.setPosition(limit);
      return null;
    }

    Cue cue = null;
    switch (sectionType) {
      case SECTION_TYPE_PALETTE:
        cueBuilder.parsePaletteSection(buffer, sectionLength);
        break;
      case SECTION_TYPE_BITMAP_PICTURE:
        if (cueBuilder.parseBitmapSection(buffer, sectionLength)) {
          cue = cueBuilder.build();
        }
        break;
      case SECTION_TYPE_IDENTIFIER:
        cueBuilder.parseIdentifierSection(buffer, sectionLength);
        break;
      case SECTION_TYPE_WINDOW_DEF:
        cueBuilder.parseWindowDefinition(buffer, sectionLength);
        break;
      case SECTION_TYPE_END:
        cueBuilder.reset();
        break;
      default:
        break;
    }

    buffer.setPosition(nextSectionPosition);
    return cue;
  }

  private static final class CueBuilder {

    private final ParsableByteArray bitmapData;
    private final int[] colors;
    private Map<Integer, Window> windows;
    private Map<Integer, PgsObject> objects;

    private boolean colorsSet;
    private int planeWidth;
    private int planeHeight;
    private int bitmapWidth;
    private int bitmapHeight;
    private int bitmapId;

    public CueBuilder() {
      bitmapData = new ParsableByteArray();
      colors = new int[256];
    }

    private void parsePaletteSection(ParsableByteArray buffer, int sectionLength) {
      if ((sectionLength % 5) != 2) {
        // Section must be two bytes then a whole number of (index, Y, Cr, Cb, alpha) entries.
        return;
      }
      buffer.skipBytes(2);

      Arrays.fill(colors, 0);
      int entryCount = sectionLength / 5;
      for (int i = 0; i < entryCount; i++) {
        int index = buffer.readUnsignedByte();
        int y = buffer.readUnsignedByte();
        int cr = buffer.readUnsignedByte();
        int cb = buffer.readUnsignedByte();
        int a = buffer.readUnsignedByte();
        int r = (int) (y + (1.40200 * (cr - 128)));
        int g = (int) (y - (0.34414 * (cb - 128)) - (0.71414 * (cr - 128)));
        int b = (int) (y + (1.77200 * (cb - 128)));
        colors[index] =
            (a << 24)
                | (Util.constrainValue(r, 0, 255) << 16)
                | (Util.constrainValue(g, 0, 255) << 8)
                | Util.constrainValue(b, 0, 255);
      }
      colorsSet = true;
    }

    // Returns true if bitmapData is completed
    private boolean parseBitmapSection(ParsableByteArray buffer, int sectionLength) {
      if (sectionLength < 4) {
        return false;
      }
      bitmapId = buffer.readUnsignedShort();
      buffer.skipBytes(1); // version (1 byte).
      int sequenceFlag = buffer.readUnsignedByte();
      boolean isBaseSection = (SEQUENCE_FIRST & sequenceFlag) != 0;
      sectionLength -= 4;

      if (isBaseSection) {
        if (sectionLength < 7) {
          return false;
        }
        int totalLength = buffer.readUnsignedInt24();
        if (totalLength < 4) {
          return false;
        }
        bitmapWidth = buffer.readUnsignedShort();
        bitmapHeight = buffer.readUnsignedShort();
        bitmapData.reset(totalLength - 4);
        sectionLength -= 7;
      }

      int position = bitmapData.getPosition();
      int limit = bitmapData.limit();
      if (position < limit && sectionLength > 0) {
        int bytesToRead = min(sectionLength, limit - position);
        buffer.readBytes(bitmapData.getData(), position, bytesToRead);
        bitmapData.setPosition(position + bytesToRead);
      }
      return (SEQUENCE_LAST & sequenceFlag) != 0;
    }

    private void parseIdentifierSection(ParsableByteArray buffer, int sectionLength) {
      if (sectionLength < 19) {
        return;
      }
      planeWidth = buffer.readUnsignedShort();
      planeHeight = buffer.readUnsignedShort();
      buffer.skipBytes(6);
      int objectLength = buffer.readUnsignedByte();
      objects = new HashMap(objectLength);
      for (int i = 0; i < objectLength; i++) {
        PgsObject object = new PgsObject();
        object.id = buffer.readUnsignedShort();
        object.windowId = buffer.readUnsignedByte();
        boolean cropped = buffer.readUnsignedByte() == 0x40;
        object.positionX = buffer.readUnsignedShort();
        object.positionY = buffer.readUnsignedShort();
        if (cropped) {
          buffer.skipBytes(8); // cropping position x, y, width, height
        }
        objects.put(object.id, object);
      }
    }

    private void parseWindowDefinition(ParsableByteArray buffer, int sectionLength) {
      if (sectionLength < 10) {
        return;
      }
      int windowLength = buffer.readUnsignedByte();
      windows = new HashMap(windowLength);
      for (int i = 0; i < windowLength; i++) {
        Window window = new Window();
        window.id = buffer.readUnsignedByte();
        window.positionX = buffer.readUnsignedShort();
        window.positionY = buffer.readUnsignedShort();
        window.width = buffer.readUnsignedShort();
        window.height = buffer.readUnsignedShort();
        windows.put(window.id, window);
      }
    }

    @Nullable
    public Cue build() {
      if (planeWidth == 0
          || planeHeight == 0
          || bitmapWidth == 0
          || bitmapHeight == 0
          || bitmapData.limit() == 0
          || bitmapData.getPosition() != bitmapData.limit()
          || !colorsSet) {
        return null;
      }
      // Build the bitmapData.
      bitmapData.setPosition(0);
      int[] argbBitmapData = new int[bitmapWidth * bitmapHeight];
      int argbBitmapDataIndex = 0;
      while (argbBitmapDataIndex < argbBitmapData.length) {
        int colorIndex = bitmapData.readUnsignedByte();
        if (colorIndex != 0) {
          argbBitmapData[argbBitmapDataIndex++] = colors[colorIndex];
        } else {
          int switchBits = bitmapData.readUnsignedByte();
          if (switchBits != 0) {
            int runLength =
                (switchBits & 0x40) == 0
                    ? (switchBits & 0x3F)
                    : (((switchBits & 0x3F) << 8) | bitmapData.readUnsignedByte());
            int color = (switchBits & 0x80) == 0 ? 0 : colors[bitmapData.readUnsignedByte()];
            Arrays.fill(
                argbBitmapData, argbBitmapDataIndex, argbBitmapDataIndex + runLength, color);
            argbBitmapDataIndex += runLength;
          }
        }
      }
      Bitmap bitmap =
          Bitmap.createBitmap(argbBitmapData, bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
      PgsObject object = objects.get(bitmapId);
      // Build the cue.
      return object == null ? null : new Cue.Builder()
          .setBitmap(bitmap)
          .setPosition((float) object.positionX / planeWidth)
          .setPositionAnchor(Cue.ANCHOR_TYPE_START)
          .setLine((float) object.positionY / planeHeight, Cue.LINE_TYPE_FRACTION)
          .setLineAnchor(Cue.ANCHOR_TYPE_START)
          .setSize((float) bitmapWidth / planeWidth)
          .setBitmapHeight((float) bitmapHeight / planeHeight)
          .build();
    }

    public void reset() {
      planeWidth = 0;
      planeHeight = 0;
      bitmapWidth = 0;
      bitmapHeight = 0;
      bitmapId = -1;
      bitmapData.reset(0);
      colorsSet = false;
      objects = null;
      windows = null;
    }

    private static final class Window {
      int id;
      int positionX;
      int positionY;
      int width;
      int height;
    }

    private static final class PgsObject {
      int id;
      int windowId;
      int positionX;
      int positionY;
    }
  }
}
