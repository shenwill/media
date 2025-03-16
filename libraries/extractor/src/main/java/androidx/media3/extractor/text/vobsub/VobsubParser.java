/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.text.vobsub;

import static java.lang.Math.min;
import static java.nio.charset.StandardCharsets.UTF_8;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Format.CueReplacementBehavior;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Inflater;

/** A {@link SubtitleParser} for Vobsub subtitles. */
@UnstableApi
public final class VobsubParser implements SubtitleParser {

  /**
   * The {@link CueReplacementBehavior} for consecutive {@link CuesWithTiming} emitted by this
   * implementation.
   */
  public static final @CueReplacementBehavior int CUE_REPLACEMENT_BEHAVIOR =
      Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE;

  private static final String TAG = "VobsubParser";
  private static final int DEFAULT_DURATION_US = 5_000_000;

  private final ParsableByteArray scratch;
  private final ParsableByteArray inflatedScratch;
  private final CueBuilder cueBuilder;
  @Nullable private Inflater inflater;
  private final boolean hasIdx;
  private final boolean isFile;
  private final int languageIndex;
  private List<Long> timestamps = new ArrayList();
  private List<Integer> fileOffsets = new ArrayList();
  private int delayMs = 0;
  private boolean idxHasPlane;
  private int @MonotonicNonNull [] idxPalette;
  private int idxPlaneWidth;
  private int idxPlaneHeight;

  public VobsubParser(List<byte[]> initializationData) {
    scratch = new ParsableByteArray();
    inflatedScratch = new ParsableByteArray();
    hasIdx = initializationData != null && initializationData.size() > 0;
    isFile = initializationData != null && initializationData.size() >= 2;

    if (hasIdx) {
      if (isFile) {
        languageIndex = readIdxFromFile(initializationData);
        cueBuilder = new CueBuilder(idxPalette, idxHasPlane, idxPlaneWidth, idxPlaneHeight);
      } else {
        cueBuilder = new CueBuilder();
        languageIndex = C.INDEX_UNSET;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
          cueBuilder.parseIdx(new String(initializationData.get(0), UTF_8));
        } else {
          cueBuilder.parseIdx(new String(initializationData.get(0)));
        }
      }
    } else {
      cueBuilder = null;
      languageIndex = C.INDEX_UNSET;
    }
  }

  @Override
  public @CueReplacementBehavior int getCueReplacementBehavior() {
    return CUE_REPLACEMENT_BEHAVIOR;
  }

  @Override
  public void parse(
      byte[] data,
      int offset,
      int length,
      OutputOptions outputOptions,
      Consumer<CuesWithTiming> output) {
    if (!hasIdx) {
      Log.e(TAG, "idx is absent");
      return;
    }
    scratch.reset(data, offset + length);
    scratch.setPosition(offset);
    if (isFile) {
      parseSubFile(output);
      return;
    }
    @Nullable Cue cue = parse();
    output.accept(
        new CuesWithTiming(
            cue != null ? ImmutableList.of(cue) : ImmutableList.of(),
            /* startTimeUs= */ C.TIME_UNSET,
            /* durationUs= */ DEFAULT_DURATION_US));
  }

  private void parseSubFile(Consumer<CuesWithTiming> output) {
    if (Util.maybeInflate(scratch, inflatedScratch, inflater)) {
      scratch.reset(inflatedScratch.getData(), inflatedScratch.limit());
    }
    int cueLength = timestamps.size();
    int totalBytes = scratch.bytesLeft();
    int i = 0;
    ParsableByteArray spuBuffer = new ParsableByteArray();
    while (i < cueLength && fileOffsets.get(i) < totalBytes) {
      scratch.setPosition(fileOffsets.get(i));
      int offsetLimit = i + 1 < fileOffsets.size() ? fileOffsets.get(i + 1) : totalBytes;
      spuBufferFromMpegStream(spuBuffer, scratch, languageIndex, offsetLimit);
      readSpuFromMpegStream(spuBuffer);
      i = parseControlFromMpegStream(output, spuBuffer, i);
      spuBuffer.reset(new byte[0]);
    }
  }

  @Nullable
  private Cue parse() {
    if (inflater == null) {
      inflater = new Inflater();
    }
    if (Util.maybeInflate(scratch, inflatedScratch, inflater)) {
      scratch.reset(inflatedScratch.getData(), inflatedScratch.limit());
    }
    cueBuilder.reset();
    int bytesLeft = scratch.bytesLeft();
    if (bytesLeft < 2 || scratch.readUnsignedShort() != bytesLeft) {
      return null;
    }
    cueBuilder.parseSpu(scratch);
    return cueBuilder.build(scratch);
  }

  private int readIdxFromFile(List<byte[]> dataList) {
    ParsableByteArray buffer = new ParsableByteArray(dataList.get(0));
    int langIndexSelected =
        dataList.size() > 1 && dataList.get(1).length > 0 ? dataList.get(1)[0] : C.INDEX_UNSET;
    boolean langHit = false;
    while (true) {
      String line = buffer.readLine();
      if (line == null) {
        return langIndexSelected;
      }
      if (line.startsWith("#") || line.length() == 0) {
        continue;
      }
      String[] stringHolder = new String[2];
      if (testIdxLine(line, "size: ", stringHolder)) {
        String[] values = stringHolder[1].trim().split("x");
        if (values.length == 2) {
          idxPlaneWidth = Integer.parseInt(values[0]);
          idxPlaneHeight = Integer.parseInt(values[1]);
          idxHasPlane = true;
        }
      } else if (testIdxLine(line, "palette: ", stringHolder)) {
        String[] values = stringHolder[1].trim().split(",");
        idxPalette = new int[values.length];
        for (int i = 0; i < values.length; i++) {
          idxPalette[i] = CueBuilder.parseColor(values[i].trim());
        }
      } else if (testIdxLine(line, "delay: ", stringHolder)
          || testIdxLine(line, "time offset: ", stringHolder)) {
        delayMs = Integer.parseInt(stringHolder[1].trim());
      } else if (testIdxLine(line, "langidx: ", stringHolder)) {
        if (langIndexSelected == C.INDEX_UNSET) {
          langIndexSelected = Integer.parseInt(stringHolder[1].trim());
        }
      } else if (testIdxLine(line, "id: ", stringHolder)) { // id: en, index: 0
        String[] parts = line.split(":");
        // can quit right now since need to count language number
        langHit = Integer.parseInt(parts[2].trim()) == langIndexSelected;
      } else if (testIdxLine(line, "timestamp: ", stringHolder)) {
        if (langHit) {
          String[] parts = line.split(":");
          timestamps.add((Long.parseLong(parts[1].trim()) * 3600000
              + Long.parseLong(parts[2]) * 60000
              + Long.parseLong(parts[3]) * 1000
              + Long.parseLong(parts[4].substring(0, 3))) * 1000);
          fileOffsets.add(Integer.parseInt(parts[5].trim(), 16));
        }
      }
    }
  }

  private boolean testIdxLine(
      @NonNull String line, @NonNull String prefixToTest, @NonNull String[] partsHolder) {
    if (line.startsWith(prefixToTest)) {
      partsHolder[0] = prefixToTest;
      partsHolder[1] = line.substring(prefixToTest.length());
      return true;
    }
    return false;
  }

  private static void spuBufferFromMpegStream(
      ParsableByteArray spuBuffer, ParsableByteArray buffer, int langIndex, int offsetLimit) {
    while (buffer.getPosition() < offsetLimit) {
      long headId = buffer.readUnsignedInt();
      if (headId == 0x000001ba) { // PS Package
        buffer.skipBytes(9);
        int stuffingLength = buffer.readUnsignedByte() & 0x07;
        buffer.skipBytes(stuffingLength);
      } else if (headId == 0x000001bb) { // PS System Header
        buffer.skipBytes(buffer.readUnsignedShort());
      } else if (headId == 0x000001bd) { // PES Package
        // (2 bytes) flags + (1 byte) head data length + (0-n bytes) header data
        // + (1 byte) stuff flags + (0-n bytes) stuff
        int packageLength = buffer.readUnsignedShort();
        buffer.readUnsignedShort(); // PES flags
        int headDataLength = buffer.readUnsignedByte();
        buffer.skipBytes(headDataLength);
        int subFlag = buffer.readUnsignedByte();
        Assertions.checkState((subFlag & 0x20) == 0x20);
        int currentPackageDataLength = packageLength - 3 - headDataLength - 1;
        if ((subFlag & 0x1f) != langIndex) {
          buffer.skipBytes(currentPackageDataLength);
          continue;
        }
        if (spuBuffer.getData().length == 0) {
          int completeDataLength = buffer.readUnsignedShort();
          buffer.setPosition(buffer.getPosition() - 2);
          spuBuffer.reset(new byte[completeDataLength], 0);
        }
        if (spuBuffer.limit() + currentPackageDataLength <= spuBuffer.capacity()) {
          buffer.readBytes(spuBuffer.getData(), spuBuffer.limit(), currentPackageDataLength);
          spuBuffer.setLimit(spuBuffer.limit() + currentPackageDataLength);
          if (spuBuffer.limit() >= spuBuffer.capacity()) {
            return;
          }
        } else {
          Log.w(TAG, "spuBufferFromMpegStream: exceeding spuBuffer capacity.");
          return;
        }
      } else if (headId == 0x000001be) { // padding stream
        int paddingLength = buffer.readUnsignedShort();
        buffer.skipBytes(paddingLength);
      } else {
        Log.w(TAG, String.format("unexpected head id: %x" + headId));
      }
    }
  }

  @Nullable
  private void readSpuFromMpegStream(ParsableByteArray buffer) {
    int spuLength = buffer.readUnsignedShort();
    Assertions.checkState(spuLength == buffer.bytesLeft() + 2);
    int controlSectionOffset = buffer.readUnsignedShort();
    buffer.setPosition(controlSectionOffset);
  }

  private int parseControlFromMpegStream(
      Consumer<CuesWithTiming> output, ParsableByteArray buffer, int i) {

    int previousCmdTime = 0;
    Cue previousCue = null;
    while (buffer.bytesLeft() >= 4) {
      int cmdDate = buffer.readUnsignedShort();
      int cmdNextOffset = buffer.readUnsignedShort();
      int commands = cueBuilder.readControlSequenceFromMpegStream(buffer, cmdDate);
      boolean error = commands < 0;
      boolean cmdStop = !error && (commands & (1 << cueBuilder.CMD_STOP)) != 0;
      boolean reachingEnd = buffer.getPosition() > cmdNextOffset || buffer.bytesLeft() < 4;
      boolean startAgain = !error && previousCue != null
          && (commands & (1 << cueBuilder.CMD_START)) != 0;
      boolean colorChanged = (commands & (1 << cueBuilder.CMD_ALPHA)) != 0
          || (commands & (1 << cueBuilder.CMD_COLORS)) != 0;
      // output previous cue since the duration is determined at this moment
      if (previousCue != null &&
          (error || cmdStop || startAgain || colorChanged || reachingEnd)) {
        long startTimeUs = timestamps.get(i) + convertTime(previousCmdTime);
        long durationUs = cmdStop ? convertTime(cmdDate - previousCmdTime) : C.TIME_UNSET;
        outputCue(output, previousCue, startTimeUs, durationUs);
        previousCue = null;
      }
      if (error) {
        cueBuilder.reset();
        i++;
        break;
      }
      if (colorChanged) {
        Cue cue = cueBuilder.buildForSubFile(buffer, false);
        if (cmdStop || reachingEnd) {
          long startTimeUs = timestamps.get(i) + delayMs * 1000 + convertTime(cmdDate);
          long durationUs = cmdStop ? 1 : C.TIME_UNSET;
          outputCue(output, cue, startTimeUs, durationUs);
        } else {
          previousCue = cue;
          previousCmdTime = cmdDate;
        }
      }
      if (cmdStop) {
        cueBuilder.reset();
      }
      if (cmdStop || startAgain || reachingEnd) {
        i++;
      }
      if (buffer.getPosition() > cmdNextOffset) {
        break;
      }
    }
    return i;
  }

  private static int convertTime(int previousCmdTime) {
    return (previousCmdTime << 10) / 90 * 1000;
  }

  private void outputCue(
      Consumer<CuesWithTiming> output, Cue cue, long startTimeUs, long durationUs) {
    CuesWithTiming cuesWithTiming = new CuesWithTiming(
        ImmutableList.of(cue), startTimeUs + delayMs * 1000, durationUs);
    output.accept(cuesWithTiming);
  }

  private static final class CueBuilder {

    private static final int CMD_FORCE_START = 0;
    private static final int CMD_START = 1;
    private static final int CMD_STOP = 2;
    private static final int CMD_COLORS = 3;
    private static final int CMD_ALPHA = 4;
    private static final int CMD_AREA = 5;
    private static final int CMD_OFFSETS = 6;
    private static final int CMD_END = 255;

    private final int[] colors;
    private boolean hasPlane;
    private boolean hasColors;
    private int @MonotonicNonNull [] palette;
    private int planeWidth;
    private int planeHeight;
    @Nullable
    private Rect boundingBox;
    private int dataOffset0;
    private int dataOffset1;

    public CueBuilder() {
      colors = new int[4];
      dataOffset0 = C.INDEX_UNSET;
      dataOffset1 = C.INDEX_UNSET;
    }

    public CueBuilder(int[] palette, boolean hasPlane, int planeWidth, int planeHeight) {
      this();
      this.palette = palette;
      this.hasPlane = hasPlane;
      this.planeWidth = planeWidth;
      this.planeHeight = planeHeight;
    }

    public void parseIdx(String idx) {
      for (String line : Util.split(idx.trim(), "\\r?\\n")) {
        if (line.startsWith("palette: ")) {
          String[] values = Util.split(line.substring("palette: ".length()), ",");
          palette = new int[values.length];

          for (int i = 0; i < values.length; i++) {
            palette[i] = parseColor(values[i].trim());
          }
        } else if (line.startsWith("size: ")) {
          // We need this line to calculate the relative positions and size required when building
          // the Cue below.
          String[] sizes = Util.split(line.substring("size: ".length()).trim(), "x");

          if (sizes.length == 2) {
            try {
              planeWidth = Integer.parseInt(sizes[0]);
              planeHeight = Integer.parseInt(sizes[1]);
              hasPlane = true;
            } catch (RuntimeException e) {
              Log.w(TAG, "Parsing IDX failed", e);
            }
          }
        }
      }
    }

    private static int parseColor(String value) {
      try {
        return Integer.parseInt(value, 16);
      } catch (RuntimeException e) {
        return 0;
      }
    }

    public void parseSpu(ParsableByteArray buffer) {
      if (palette == null || !hasPlane) {
        // Give up if we don't have the color palette or the video size.
        return;
      }
      int[] palette = this.palette;
      buffer.skipBytes(buffer.readUnsignedShort() - 2);
      int end = buffer.readUnsignedShort();
      parseControl(palette, buffer, end);
    }

    private void parseControl(int[] palette, ParsableByteArray buffer, int end) {
      while (buffer.getPosition() < end && buffer.bytesLeft() > 0) {
        switch (buffer.readUnsignedByte()) {
          case CMD_COLORS:
            if (!parseControlColors(palette, buffer)) {
              return;
            }
            break;
          case CMD_ALPHA:
            if (!parseControlAlpha(buffer)) {
              return;
            }
            break;
          case CMD_AREA:
            if (!parseControlArea(buffer)) {
              return;
            }
            break;
          case CMD_OFFSETS:
            if (!parseControlOffsets(buffer)) {
              return;
            }
            break;
          case CMD_FORCE_START:
          case CMD_START:
          case CMD_STOP:
            // ignore unused commands without arguments
            break;
          case CMD_END:
          default:
            return;
        }
      }
    }

    private boolean parseControlColors(int[] palette, ParsableByteArray buffer) {
      if (buffer.bytesLeft() < 2) {
        return false;
      }

      int byte0 = buffer.readUnsignedByte();
      int byte1 = buffer.readUnsignedByte();

      colors[3] = getColor(palette, byte0 >> 4);
      colors[2] = getColor(palette, byte0 & 0xf);
      colors[1] = getColor(palette, byte1 >> 4);
      colors[0] = getColor(palette, byte1 & 0xf);
      hasColors = true;

      return true;
    }

    private static int getColor(int[] palette, int index) {
      return index >= 0 && index < palette.length ? palette[index] : palette[0];
    }

    private boolean parseControlAlpha(ParsableByteArray buffer) {

      if (buffer.bytesLeft() < 2 || !hasColors) {
        return false;
      }

      int byte0 = buffer.readUnsignedByte();
      int byte1 = buffer.readUnsignedByte();

      colors[3] = setAlpha(colors[3], (byte0 >> 4));
      colors[2] = setAlpha(colors[2], (byte0 & 0xf));
      colors[1] = setAlpha(colors[1], (byte1 >> 4));
      colors[0] = setAlpha(colors[0], (byte1 & 0xf));

      return true;
    }

    private static int setAlpha(int color, int alpha) {
      return ((color & 0x00ffffff) | ((alpha * 17) << 24));
    }

    private boolean parseControlArea(ParsableByteArray buffer) {
      if (buffer.bytesLeft() < 6) {
        return false;
      }

      int byte0 = buffer.readUnsignedByte();
      int byte1 = buffer.readUnsignedByte();
      int byte2 = buffer.readUnsignedByte();

      int left = (byte0 << 4) | (byte1 >> 4);
      int right = ((byte1 & 0xf) << 8) | byte2;

      int byte3 = buffer.readUnsignedByte();
      int byte4 = buffer.readUnsignedByte();
      int byte5 = buffer.readUnsignedByte();

      int top = (byte3 << 4) | (byte4 >> 4);
      int bottom = ((byte4 & 0xf) << 8) | byte5;

      boundingBox = new Rect(left, top, right + 1, bottom + 1);

      return true;
    }

    private boolean parseControlOffsets(ParsableByteArray buffer) {
      if (buffer.bytesLeft() < 4) {
        return false;
      }

      dataOffset0 = buffer.readUnsignedShort();
      dataOffset1 = buffer.readUnsignedShort();

      return true;
    }

    @Nullable
    public Cue build(ParsableByteArray buffer) {
      if (palette == null
          || !hasPlane
          || !hasColors
          || boundingBox == null
          || dataOffset0 == C.INDEX_UNSET
          || dataOffset1 == C.INDEX_UNSET
          || boundingBox.width() < 2
          || boundingBox.height() < 2) {
        return null;
      }
      Rect boundingBox = this.boundingBox;
      int[] bitmapData = new int[boundingBox.width() * boundingBox.height()];
      ParsableBitArray bitBuffer = new ParsableBitArray();

      buffer.setPosition(dataOffset0);
      bitBuffer.reset(buffer);
      parseRleData(bitBuffer, /* evenInterlace= */ true, boundingBox, bitmapData);
      buffer.setPosition(dataOffset1);
      bitBuffer.reset(buffer);
      parseRleData(bitBuffer, /* evenInterlace= */ false, boundingBox, bitmapData);

      Bitmap bitmap =
          Bitmap.createBitmap(
              bitmapData, boundingBox.width(), boundingBox.height(), Bitmap.Config.ARGB_8888);

      return new Cue.Builder()
          .setBitmap(bitmap)
          .setPosition((float) boundingBox.left / planeWidth)
          .setPositionAnchor(Cue.ANCHOR_TYPE_START)
          .setLine((float) boundingBox.top / planeHeight, Cue.LINE_TYPE_FRACTION)
          .setLineAnchor(Cue.ANCHOR_TYPE_START)
          .setSize((float) boundingBox.width() / planeWidth)
          .setBitmapHeight((float) boundingBox.height() / planeHeight)
          .build();
    }

    /**
     * Parse run-length encoded data into the {@code bitmapData} array. The subtitle bitmap is
     * encoded in two blocks of interlaced lines, {@code y} gives the index of the starting line (0
     * or 1).
     *
     * @param bitBuffer The RLE encoded data.
     * @param evenInterlace Whether to decode the even or odd interlaced lines.
     * @param bitmapData Output array.
     */
    private void parseRleData(
        ParsableBitArray bitBuffer, boolean evenInterlace, Rect boundingBox, int[] bitmapData) {
      int width = boundingBox.width();
      int height = boundingBox.height();
      int x = 0;
      int y = evenInterlace ? 0 : 1;
      int outIndex = y * width;
      Run run = new Run();

      while (true) {
        parseRun(bitBuffer, width, run);

        int length = min(run.length, width - x);
        if (length > 0) {
          Arrays.fill(bitmapData, outIndex, outIndex + length, colors[run.colorIndex]);
          outIndex += length;
          x += length;
        }
        if (x >= width) {
          y += 2;
          if (y >= height) {
            break;
          }
          x = 0;
          outIndex = y * width;
          bitBuffer.byteAlign();
        }
      }
    }

    private static void parseRun(ParsableBitArray bitBuffer, int width, Run output) {
      int value = 0;
      int test = 1;

      while (value < test && test <= 0x40) {
        if (bitBuffer.bitsLeft() < 4) {
          output.colorIndex = C.INDEX_UNSET;
          output.length = 0;
          return;
        }
        value = (value << 4) | bitBuffer.readBits(4);
        test <<= 2;
      }
      output.colorIndex = value & 3;
      output.length = value < 4 ? width : (value >> 2);
    }

    public void reset() {
      hasColors = false;
      boundingBox = null;
      dataOffset0 = C.INDEX_UNSET;
      dataOffset1 = C.INDEX_UNSET;
    }

    private static final class Run {
      public int colorIndex;
      public int length;
    }

    // following for idx/sub files

    private int readControlSequenceFromMpegStream(ParsableByteArray buffer, int cmdDate) {
      int cmdFlag = 0;
      boolean go = true;
      while (buffer.bytesLeft() > 0 && go) {
        int cmd = buffer.readUnsignedByte();
        switch (cmd) {
          case CMD_FORCE_START:
            // forceDisplay = true;
            cmdFlag |= 1 << CMD_FORCE_START;
            break;
          case CMD_START:
            // dateStart = cmdDate;
            cmdFlag |= 1 << CMD_START;
            break;
          case CMD_STOP:
            cmdFlag |= 1 << CMD_STOP;
            break;
          case CMD_COLORS:
            cmdFlag |= 1 << CMD_COLORS;
            if (!parseControlColors(palette, buffer)) {
              return -3;
            }
            break;
          case CMD_ALPHA:
            cmdFlag |= 1 << CMD_ALPHA;
            if (!parseControlAlpha(buffer)) {
              return -4;
            }
            break;
          case CMD_AREA:
            cmdFlag |= 1 << CMD_AREA;
            if (!parseControlArea(buffer)) {
              return -5;
            }
            break;
          case CMD_OFFSETS:
            cmdFlag |= 1 << CMD_OFFSETS;
            if (!parseControlOffsets(buffer)) {
              return -6;
            }
            break;
          case 7: // unimplemented
          case CMD_END: // end
            go = false;
            break;
          default:
            // unexpected cmd
            return -cmd;
        }
      }
      return cmdFlag;
    }

    @Nullable
    public Cue buildForSubFile(ParsableByteArray buffer, boolean draw) {
      if (palette == null
          || !hasPlane
          || !hasColors
          || boundingBox == null
          || dataOffset0 == C.INDEX_UNSET
          || dataOffset1 == C.INDEX_UNSET
          || boundingBox.width() < 2
          || boundingBox.height() < 2) {
        return null;
      }

      Bitmap bitmap = null;
      RleBitmapContext bitmapContext = new RleBitmapContext(
          boundingBox.width(), boundingBox.height(), dataOffset0, dataOffset1, colors);
      if (draw) {
        bitmap = bitmapContext.draw(buffer);
      } else {
        bitmapContext.buffer = new ParsableByteArray(buffer.getData());
      }

      // Build the cue.
      return new Cue.Builder()
          .setBitmap(bitmap)
          .setBitmapDrawContext(draw ? null : bitmapContext)
          .setPosition((float) boundingBox.left / planeWidth)
          .setPositionAnchor(Cue.ANCHOR_TYPE_START)
          .setLine((float) boundingBox.top / planeHeight, Cue.LINE_TYPE_FRACTION)
          .setLineAnchor(Cue.ANCHOR_TYPE_START)
          .setSize((float) boundingBox.width() / planeWidth)
          .setBitmapHeight((float) boundingBox.height() / planeHeight)
          .build();
    }
  }

  public static final class RleBitmapContext implements Cue.IBitmapDrawContext {

    ParsableByteArray buffer;
    final int bitmapWidth, bitmapHeight, dataOffsetEven, dataOffsetOdd;
    final int[] palette;

    public RleBitmapContext(int bitmapWidth, int bitmapHeight,
                            int dataOffsetEven, int dataOffsetOdd,
                            int[] palette) {
      this.bitmapWidth = bitmapWidth;
      this.bitmapHeight = bitmapHeight;
      this.dataOffsetEven = dataOffsetEven;
      this.dataOffsetOdd = dataOffsetOdd;
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
