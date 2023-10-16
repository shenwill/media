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
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
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

  private static final String TAG = "VobsubParser";
  private static final byte INFLATE_HEADER = 0x78;

  private final ParsableByteArray buffer;
  private final ParsableByteArray inflatedBuffer;
  private final CueBuilder cueBuilder;
  @Nullable private Inflater inflater;
  private final boolean hasIdx;

  public VobsubParser(@Nullable List<byte[]> initializationData) {
    buffer = new ParsableByteArray();
    inflatedBuffer = new ParsableByteArray();
    cueBuilder = new CueBuilder();
    hasIdx = initializationData != null && initializationData.size() > 0;
    if (hasIdx) {
      cueBuilder.readIdx(initializationData);
    }
  }

  @Override
  public void reset() {}
  @Override
  public ImmutableList<CuesWithTiming> parse(byte[] data, int offset, int length) {
    if (!hasIdx) {
      Log.e(TAG, "idx is absent");
      return ImmutableList.of();
    }
    buffer.reset(data, /* limit= */ offset + length);
    buffer.setPosition(offset);
    if (buffer.peekUnsignedByte() == INFLATE_HEADER) {
      if (!inflateData(buffer)) {
        return ImmutableList.of();
      }
    }
    if (cueBuilder.timestamps.size() > 0) {
      int cueLength = cueBuilder.timestamps.size();
      int totalBytes = buffer.bytesLeft();
      int i = 0;
      List<CuesWithTiming> cuesWithTimings = new ArrayList();
      ParsableByteArray spuBuffer = new ParsableByteArray();
      while (i < cueLength && cueBuilder.fileOffsets.get(i) < totalBytes) {
        buffer.setPosition(cueBuilder.fileOffsets.get(i));
        spuBufferFromMpegStream(spuBuffer, buffer, cueBuilder);
        cueBuilder.resetBmp();
        Cue cue = readSpu(spuBuffer, cueBuilder, false);
        spuBuffer.reset(new byte[0]);
        if (cue != null) {
          cuesWithTimings.add(new CuesWithTiming(ImmutableList.of(cue),
              cueBuilder.timestamps.get(i) + cueBuilder.delayMs * 1000,
              cueBuilder.dateStop * 10000));
        }
        i++;
      }
      return ImmutableList.copyOf(cuesWithTimings);
    }
    cueBuilder.resetBmp();
    Cue cue = readSpu(buffer, cueBuilder, true);
    return cue == null ? ImmutableList.of() : ImmutableList.of(
        new CuesWithTiming(ImmutableList.of(cue), C.TIME_UNSET, cueBuilder.dateStop * 10000));
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

  private static void spuBufferFromMpegStream(
      ParsableByteArray spuBuffer, ParsableByteArray buffer, CueBuilder cueBuilder) {
    while (true) {
      long headId = buffer.readUnsignedInt();
      if (headId == 0x000001ba) { // PS Package
        buffer.skipBytes(9);
        int stuffingLength = buffer.readUnsignedByte() & 0x07;
        buffer.skipBytes(stuffingLength);
      } else if (headId == 0x000001bb) { // PS System Header
        buffer.skipBytes(buffer.readUnsignedShort());
      } else if (headId == 0x000001bd) { // PES Package
        int packageLength = buffer.readUnsignedShort();
        buffer.readUnsignedShort(); // PES flags
        int headDataLength = buffer.readUnsignedByte();
        buffer.skipBytes(headDataLength);
        int dataLength = packageLength - 3 - headDataLength;
        Assertions.checkState(buffer.readUnsignedByte() == 0x20);
        if (spuBuffer.getData().length == 0) {
          spuBuffer.reset(new byte[buffer.readUnsignedShort()], dataLength - 1);
          buffer.setPosition(buffer.getPosition() - 2);
          buffer.readBytes(spuBuffer.getData(), 0, dataLength - 1);
        } else {
          buffer.readBytes(spuBuffer.getData(), spuBuffer.limit(), dataLength - 1);
          spuBuffer.reset(spuBuffer.limit() + dataLength - 1);
        }
        if (spuBuffer.limit() == spuBuffer.getData().length) {
          return;
        }
      } else if (headId == 0x000001be) { // padding stream
        int paddingLength = buffer.readUnsignedShort();
        buffer.skipBytes(paddingLength);
      }
    }
  }

  @Nullable
  private static Cue readSpu(ParsableByteArray buffer, CueBuilder cueBuilder, boolean draw) {
    int spuLength = buffer.readUnsignedShort();
    Assertions.checkState(spuLength == buffer.bytesLeft() + 2);
    int controlSectionOffset = buffer.readUnsignedShort();
    int graphicDataOffset = buffer.getPosition();
    buffer.setPosition(controlSectionOffset);
    cueBuilder.parseControlSection(buffer);
    buffer.setPosition(graphicDataOffset);

    Cue cue = cueBuilder.build(buffer, draw);

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
    private int delayMs = 0;
    private boolean forceDisplay;
    private int[] palette;
    private int planeWidth;
    private int planeHeight;
    private int bitmapX;
    private int bitmapY;
    private int bitmapWidth;
    private int bitmapHeight;
    private List<Long> timestamps = new ArrayList();
    private List<Integer> fileOffsets = new ArrayList();
    private ParsableByteArray buffer;
    private int bufferLimit;

    public CueBuilder() {
      colors = new int[16];
    }

    private void parseControlSection(ParsableByteArray buffer) {
      while (buffer.bytesLeft() >= 4) {
        int cmdDate = buffer.readUnsignedShort();
        int cmdNextOffset = buffer.readUnsignedShort();
        readControlSequence(buffer, cmdDate);
        if (buffer.getPosition() > cmdNextOffset) {
          return;
        }
      }
    }

    private boolean readControlSequence(ParsableByteArray buffer, int cmdDate) {
      List<Integer> lastCommands = new ArrayList<>();
      while (buffer.bytesLeft() >= 1) {
        int command = buffer.readUnsignedByte();
        lastCommands.add(command);
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
      return false;
    }

    private void readIdx(List<byte[]> dataList) {
      ParsableByteArray buffer = new ParsableByteArray(dataList.get(0));
      int langIndexSelected =
          dataList.size() > 1 && dataList.get(1).length > 0 ? dataList.get(1)[0] : C.INDEX_UNSET;
      boolean langHit = false;
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
        } else if ("delay".equals(key) || "time offset".equals(key)) {
          delayMs = Integer.parseInt(parts[1].trim());
        } else if ("langidx".equals(key)) {
          if (langIndexSelected == C.INDEX_UNSET) {
            langIndexSelected = Integer.parseInt(parts[1].trim());
          }
        } else if ("id".equals(key)) { // id: en, index: 0
          langHit = Integer.parseInt(parts[2].trim()) == langIndexSelected;
        } else if ("timestamp".equals(key)) {
          if (langHit) {
            timestamps.add((Long.parseLong(parts[1].trim()) * 3600000
            + Long.parseLong(parts[2]) * 60000
            + Long.parseLong(parts[3]) * 1000
            + Long.parseLong(parts[4].substring(0, 3))) * 1000);
            fileOffsets.add(Integer.parseInt(parts[5].trim(), 16));
          }
        }
      }
    }

    @Nullable
    public Cue build(ParsableByteArray buffer, boolean draw) {
      if (planeWidth == 0
          || planeHeight == 0
          || bitmapWidth == 0
          || bitmapHeight == 0
          || buffer.bytesLeft() == 0
          || !colorsSet) {
        return null;
      }

      Bitmap bitmap = null;
      RleBitmapContext bitmapContext = new RleBitmapContext(
          bitmapWidth, bitmapHeight, dataOffsetEven, dataOffsetOdd, alpha, colors, palette);
      if (draw) {
        bitmap = bitmapContext.draw(buffer);
      } else {
        bitmapContext.buffer = new ParsableByteArray(buffer.getData());
      }

      // Build the cue.
      Cue cue = new Cue.Builder()
          .setBitmap(bitmap)
          .setBitmapDrawContext(draw ? null : bitmapContext)
          .setPosition((float) bitmapX / planeWidth)
          .setPositionAnchor(Cue.ANCHOR_TYPE_START)
          .setLine((float) bitmapY / planeHeight, Cue.LINE_TYPE_FRACTION)
          .setLineAnchor(Cue.ANCHOR_TYPE_START)
          .setSize((float) bitmapWidth / planeWidth)
          .setBitmapHeight((float) bitmapHeight / planeHeight)
          .build();
      return cue;
    }

    public void resetBmp() {
      bitmapX = 0;
      bitmapY = 0;
      bitmapWidth = 0;
      bitmapHeight = 0;
    }
  }

  public static final class RleBitmapContext implements Cue.IBitmapDrawContext {

    ParsableByteArray buffer;
    final int bitmapWidth, bitmapHeight, dataOffsetEven, dataOffsetOdd;
    final int[] alpha, colors, palette;

    public RleBitmapContext(int bitmapWidth, int bitmapHeight,
                             int dataOffsetEven, int dataOffsetOdd,
                             int[] alpha, int[] colors, int[] palette) {
      this.bitmapWidth = bitmapWidth;
      this.bitmapHeight = bitmapHeight;
      this.dataOffsetEven = dataOffsetEven;
      this.dataOffsetOdd = dataOffsetOdd;
      this.alpha = Arrays.copyOf(alpha, alpha.length);
      this.colors = colors;
      this.palette = Arrays.copyOf(palette, palette.length);
    }

    public Bitmap draw(ParsableByteArray buffer) {
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
        palette[i] = ((alpha[i] * 17) << 24) | colors[this.palette[i]];
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

    @Override
    public Bitmap draw() {
      return draw(buffer);
    }
  }
}
