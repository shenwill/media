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
package androidx.media3.extractor.text.vobsub;

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
import java.util.List;
import java.util.zip.Inflater;

/** A {@link SubtitleParser} for VobSub subtitles. */
@UnstableApi
public final class VobsubParser implements SubtitleParser {

  private static final byte INFLATE_HEADER = 0x78;

  private final ParsableByteArray buffer;
  private final ParsableByteArray inflatedBuffer;
  private final CueBuilder cueBuilder;
  @Nullable private Inflater inflater;

  public VobsubParser(@Nullable List<byte[]> initializationData) {
    buffer = new ParsableByteArray();
    inflatedBuffer = new ParsableByteArray();
    cueBuilder = new CueBuilder();
    if (initializationData.size() > 0) {
      cueBuilder.readIdx(initializationData.get(0));
    }
  }

  @Override
  public void reset() {}
  @Override
  public ImmutableList<CuesWithTiming> parse(byte[] data, int offset, int length) {
    buffer.reset(data, /* limit= */ offset + length);
    buffer.setPosition(offset);
    if (buffer.peekUnsignedByte() == INFLATE_HEADER) {
      if (!inflateData(buffer)) {
        return ImmutableList.of();
      }
    }
    cueBuilder.resetBmp();
    ArrayList<Cue> cues = new ArrayList<>();
    Cue cue = readSpu(buffer, cueBuilder);
    if (cue != null) {
      cues.add(cue);
    }
    return ImmutableList.of(
        new CuesWithTiming(cues, C.TIME_UNSET, cueBuilder.dateStop * 10000));
  }

  private boolean inflateData(ParsableByteArray buffer) {
    if (buffer.bytesLeft() > 0) {
      if (inflater == null) {
        inflater = new Inflater();
      }
      if (Util.inflate(buffer, inflatedBuffer, inflater)) {
        buffer.reset(inflatedBuffer.getData(), inflatedBuffer.limit());
        return true;
      } // else assume data is not compressed.
    }
    return false;
  }

  @Nullable
  private static Cue readSpu(ParsableByteArray buffer, CueBuilder cueBuilder) {
    int limit = buffer.limit();
    int spuLength = buffer.readUnsignedShort();
    int controlSectionOffset = buffer.readUnsignedShort();
    int graphicDataOffset = buffer.getPosition();
    buffer.setPosition(controlSectionOffset);
    cueBuilder.parseControlSection(buffer);
    buffer.setPosition(graphicDataOffset);

    Cue cue = cueBuilder.build(buffer);
    cueBuilder.resetBmp();

    return cue;
  }

  private static final class CueBuilder {

    private int[] alpha;
    private final int[] colors;
    private boolean colorsSet;
    private int dataOffsetEven;
    private int dataOffsetOdd;
    private int dateStart;
    private int dateStop;
    private boolean forceDisplay;
    private int[] palette;
    private int planeWidth;
    private int planeHeight;
    private int bitmapX;
    private int bitmapY;
    private int bitmapWidth;
    private int bitmapHeight;

    public CueBuilder() {
      colors = new int[16];
    }

    private void parseControlSection(ParsableByteArray buffer) {
      while (true) {
        int cmdDate = buffer.readUnsignedShort();
        int cmdNextOffset = buffer.readUnsignedShort();
        readControlSequence(buffer, cmdDate);
        if (buffer.getPosition() > cmdNextOffset) {
          return;
        }
      }
    }

    private boolean readControlSequence(ParsableByteArray buffer, int cmdDate) {
      while (true) {
        int command = buffer.readUnsignedByte();
        switch (command) {
          case 0:
            forceDisplay = true;
            break;
          case 1:
            dateStart = cmdDate;
            break;
          case 2:
            dateStop = cmdDate;
            break;
          case 3: {
            int data = buffer.readUnsignedShort();
            palette = new int[4];
            palette[0] = (data & 0x000F);
            palette[1] = (data & 0x00F0) >> 4;
            palette[2] = (data & 0x0F00) >> 8;
            palette[3] = (data & 0xF000) >> 12;
          }
          break;
          case 4: {
            int data = buffer.readUnsignedShort();
            alpha = new int[4];
            alpha[0] = (data & 0x000F);
            alpha[1] = (data & 0x00F0) >> 4;
            alpha[2] = (data & 0x0F00) >> 8;
            alpha[3] = (data & 0xF000) >> 12;
          }
          break;
          case 5: {
            int data = buffer.readInt24();
            bitmapX = data >> 12;
            bitmapWidth = (data & 0x000FFF) - bitmapX + 1;
            data = buffer.readInt24();
            bitmapY = data >> 12;
            bitmapHeight = (data & 0x000FFF) - bitmapY + 1;
          }
          break;
          case 6:
            dataOffsetEven = buffer.readUnsignedShort();
            dataOffsetOdd = buffer.readUnsignedShort();
            break;
          case 7: // unimplemented
            return false;
          case 0xFF: // end
            return true;
          default: // invalid
            return false;
        }
      }
    }

    private void readIdx(byte[] data) {
      ParsableByteArray buffer = new ParsableByteArray(data);
      while (true) {
        String line = buffer.readLine();
        if (line == null) {
          return;
        }
        if (line.startsWith("#") || line.length() == 0) {
          continue;
        }
        String[] parts = line.split(":");
        if (parts.length < 2) {
          continue;
        }
        String key = parts[0];
        if ("size".equals(key)) {
          String[] values = parts[1].trim().split("x");
          if (values.length == 2) {
          planeWidth = Integer.parseInt(values[0]);
          planeHeight = Integer.parseInt(values[1]);}
        } else if ("palette".equals(key)) {
          String[] values = parts[1].trim().split(",");
          int length = Math.min(colors.length, values.length);
          for (int i = 0; i < length; i++) {
            colors[i] = Integer.parseInt(values[i].trim(), 16);
          }
          colorsSet = true;
        }
      }
    }

    @Nullable
    public Cue build(ParsableByteArray buffer) {
      if (planeWidth == 0
          || planeHeight == 0
          || bitmapWidth == 0
          || bitmapHeight == 0
          || buffer.bytesLeft() == 0
          || !colorsSet) {
        return null;
      }

      Bitmap bitmap = draw(buffer);

      // Build the cue.
      return new Cue.Builder()
          .setBitmap(bitmap)
          .setPosition((float) bitmapX / planeWidth)
          .setPositionAnchor(Cue.ANCHOR_TYPE_START)
          .setLine((float) bitmapY / planeHeight, Cue.LINE_TYPE_FRACTION)
          .setLineAnchor(Cue.ANCHOR_TYPE_START)
          .setSize((float) bitmapWidth / planeWidth)
          .setBitmapHeight((float) bitmapHeight / planeHeight)
          .build();
    }

    private Bitmap draw(ParsableByteArray buffer) {
      int[] argbBitmapData = new int[bitmapWidth * bitmapHeight];
      buffer.setPosition(dataOffsetEven);
      draw(buffer, argbBitmapData, 0, 0);
      buffer.setPosition(dataOffsetOdd);
      draw(buffer, argbBitmapData, 0, 1);
      return Bitmap.createBitmap(
          argbBitmapData, bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
    }

    private void draw(ParsableByteArray buffer, int[] argbBitmapData, int x, int y) {
      int[] palette = new int[4];
      for (int i = 0; i < palette.length; i++) {
        palette[i] = ((this.alpha[i] * 17) << 24) | colors[this.palette[i]];
      }
      while (y < bitmapHeight) {
        boolean drawToEnd = false;
        int data = buffer.readNibble();
        if (data != 0) {
          if (data <= 0b11) {
            data = (data << 4) | buffer.readNibble();
          }
        } else { // nibble == 0
          data = buffer.readNibble();
          if (data != 0) {
            if (data > 0b11) {
              data = (data << 4) | buffer.readNibble();
            } else {
              data = (data << 4) | buffer.readNibble();
              data = (data << 4) | buffer.readNibble();
            }
          } else { // nibble == 0
            data = (buffer.readNibble() << 4) | buffer.readNibble();
            drawToEnd = true;
          }
        }
        int length = drawToEnd ? (bitmapWidth - x) : (data >> 2);
        int color = palette[data & 0b11];
        int start = y * bitmapWidth + x;
        Arrays.fill(argbBitmapData, start, start + length, color);
        x += length;
        if (x == bitmapWidth) {
          y += 2;
          x = 0;
          buffer.alignNibble();
        }
      }
    }

    public void resetBmp() {
      bitmapX = 0;
      bitmapY = 0;
      bitmapWidth = 0;
      bitmapHeight = 0;
    }
  }
}
