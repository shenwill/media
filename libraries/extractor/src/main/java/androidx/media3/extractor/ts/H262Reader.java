/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.extractor.ts;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkStateNotNull;

import android.util.Log;
import android.util.Pair;
import android.util.SparseIntArray;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;

import com.google.common.primitives.Longs;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Parses a continuous H262 byte stream and extracts individual frames. */
@UnstableApi
public final class H262Reader implements ElementaryStreamReader {

  private static final String TAG = "H262Reader";

  private static final int START_PICTURE = 0x00;
  private static final int START_SEQUENCE_HEADER = 0xB3;
  private static final int START_EXTENSION = 0xB5;
  private static final int START_GROUP = 0xB8;
  private static final int START_USER_DATA = 0xB2;

  private static final int PICTURE_TYPE_FRAME = 0x03;
  private static final int PICTURE_TYPE_TOP_FIELD = 0x01;
  private static final int PICTURE_TYPE_BOTTOM_FIELD = 0x02;

  private static final int FRAME_I = 1;
  private static final int FRAME_P = 2;
  private static final int FRAME_B = 3;

  private @MonotonicNonNull String formatId;
  private @MonotonicNonNull TrackOutput output;
  private @MonotonicNonNull ExtractorOutput extractorOutput;

  // Maps (frame_rate_code - 1) indices to values, as defined in ITU-T H.262 Table 6-4.
  private static final double[] FRAME_RATE_VALUES =
      new double[]{24000d / 1001, 24, 25, 30000d / 1001, 30, 50, 60000d / 1001, 60};

  @Nullable
  private final UserDataReader userDataReader;
  @Nullable
  private final ParsableByteArray userDataParsable;

  @Nullable
  private final long[] chapterTimesNs;
  // State that should be reset on seek.
  @Nullable
  private final NalUnitTargetBuffer userData;
  private final boolean[] prefixFlags;
  private final CsdBuffer csdBuffer;
  private long totalBytesWritten;
  private boolean startedFirstSample;

  // State that should not be reset on seek.
  private boolean hasOutputFormat;
  private long frameDurationUs;
  private boolean progressiveSequence;


  // Per packet state that gets reset at the start of each packet.
  private long pesTimeUs;

  // Per sample state that gets reset at the start of each sample.
  private long samplePosition;
  private long sampleTimeUs;
  private long sampleTimeBaseUs;
  private boolean sampleIsKeyframe;
  private boolean sampleHasPicture;
  private boolean adjPts;
  private int firstFieldSampleSize = 0;
  private int frameCount;

  // per every picture
  private MPEG2PictureHeader pictureHeader;
  private int fieldCount;
  private int pictureIndex;

  public H262Reader() {
    this(null, null, false);
  }

  /* package */ H262Reader(
      @Nullable UserDataReader userDataReader, byte[][] initDataBytes, boolean adjPts) {
    this.userDataReader = userDataReader;
    prefixFlags = new boolean[4];
    csdBuffer = new CsdBuffer(128);
    if (userDataReader != null) {
      userData = new NalUnitTargetBuffer(START_USER_DATA, 128);
      userDataParsable = new ParsableByteArray();
    } else {
      userData = null;
      userDataParsable = null;
    }
    chapterTimesNs = getChapterTimesNs(initDataBytes);
    frameDurationUs = C.LENGTH_UNSET;
    pesTimeUs = C.TIME_UNSET;
    sampleTimeUs = C.TIME_UNSET;
    this.adjPts = adjPts;
  }

  @Override
  public void seek() {
    NalUnitUtil.clearPrefixFlags(prefixFlags);
    csdBuffer.reset();
    if (userData != null) {
      userData.reset();
    }
    totalBytesWritten = 0;
    startedFirstSample = false;
    pesTimeUs = C.TIME_UNSET;
    sampleTimeUs = C.TIME_UNSET;
    sampleTimeBaseUs = C.TIME_UNSET;
    progressiveSequence = false;
    frameCount = 0;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    this.extractorOutput = extractorOutput;
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_VIDEO);
    if (userDataReader != null) {
      userDataReader.createTracks(extractorOutput, idGenerator);
    }
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    // TODO (Internal b/32267012): Consider using random access indicator.
    this.pesTimeUs = pesTimeUs;
  }

  @Override
  public void consume(ParsableByteArray data) {
    checkStateNotNull(output); // Asserts that createTracks has been called.
    int offset = data.getPosition();
    int limit = data.limit();
    byte[] dataArray = data.getData();

    // Append the data to the buffer.
    totalBytesWritten += data.bytesLeft();
    output.sampleData(data, data.bytesLeft());

    while (true) {
      int startCodeOffset = NalUnitUtil.findNalUnit(dataArray, offset, limit, prefixFlags);

      if (startCodeOffset == limit) {
        // We've scanned to the end of the data without finding another start code.
        if (csdBuffer.isFilling) {
          csdBuffer.onData(dataArray, offset, limit);
        }
        if (userData != null) {
          userData.appendToNalUnit(dataArray, offset, limit);
        }
        return;
      }

      // We've found a start code with the following value.
      int startCodeValue = data.getData()[startCodeOffset + 3] & 0xFF;
      // This is the number of bytes from the current offset to the start of the next start
      // code. It may be negative if the start code started in the previously consumed data.
      int lengthToStartCode = startCodeOffset - offset;

      //deal with sequence header (for picture size, ratio, rate...) and picture header (I, P, B)
      if (!hasOutputFormat || csdBuffer.isFilling
          || (startCodeValue == START_PICTURE || startCodeValue == START_EXTENSION)) {
        if (lengthToStartCode > 0) {
          csdBuffer.onData(dataArray, offset, startCodeOffset);
        }
        // This is the number of bytes belonging to the next start code that have already been
        // passed to csdBuffer.
        int bytesAlreadyPassed = lengthToStartCode < 0 ? -lengthToStartCode : 0;
        boolean completed = csdBuffer.onStartCode(startCodeValue, bytesAlreadyPassed);
        if (completed && csdBuffer.codeFilling == START_PICTURE) {
          parseCsdBufferForPictureHeader(csdBuffer);
        }
        if (!hasOutputFormat && completed && csdBuffer.codeFilling == START_SEQUENCE_HEADER) {
          // The csd data is complete, so we can decode and output the media format.
          Pair<Format, Pair> result = parseCsdBuffer(csdBuffer, checkNotNull(formatId));
          output.format(result.first);
          extractorOutput.chapters(chapterTimesNs, null);
          frameDurationUs = (long) result.second.first;
          progressiveSequence = (boolean) result.second.second;
          hasOutputFormat = true;
        }
      }
      if (userData != null) {
        int bytesAlreadyPassed = 0;
        if (lengthToStartCode > 0) {
          userData.appendToNalUnit(dataArray, offset, startCodeOffset);
        } else {
          bytesAlreadyPassed = -lengthToStartCode;
        }

        if (userData.endNalUnit(bytesAlreadyPassed)) {
          int unescapedLength = NalUnitUtil.unescapeStream(userData.nalData, userData.nalLength);
          Util.castNonNull(userDataParsable).reset(userData.nalData, unescapedLength);
          Util.castNonNull(userDataReader).consume(sampleTimeUs, userDataParsable);
        }

        if (startCodeValue == START_USER_DATA && data.getData()[startCodeOffset + 2] == 0x1) {
          userData.startNalUnit(startCodeValue);
        }
      }
      if (startCodeValue == START_PICTURE || startCodeValue == START_SEQUENCE_HEADER) {
        int bytesWrittenPastStartCode = limit - startCodeOffset;
        if (sampleHasPicture && hasOutputFormat && sampleTimeUs != C.TIME_UNSET) {
          // Output the sample.
          @C.BufferFlags int flags = sampleIsKeyframe ? C.BUFFER_FLAG_KEY_FRAME : 0;
          int size = (int) (totalBytesWritten - samplePosition) - bytesWrittenPastStartCode;
          long timeUs = sampleTimeUs;
          if (adjPts && sampleTimeBaseUs != C.TIME_UNSET) {
            if (pictureHeader.pictureIndex == 0) {
              timeUs = sampleTimeBaseUs;
              sampleTimeBaseUs = timeUs - getTimestampOffsetUs(pictureHeader.temporalReference);
            } else if (pictureHeader.temporalReference != pictureHeader.pictureIndex) {
              timeUs = sampleTimeBaseUs + getTimestampOffsetUs(pictureHeader.temporalReference);
            }
          }
          if (isFrameCompleted()) {
            size += firstFieldSampleSize;
            output.sampleMetadata(timeUs, flags, size, bytesWrittenPastStartCode, null);
            //Log.i(TAG, frameTypesByFieldStringBuilder.charAt(frameTypesByFieldStringBuilder.length() - 1)
            //    + " " + pictureHeader.temporalReference + "-" + pictureHeader.pictureIndex
            //    + "---===sampleMetadata timeUs=" + timeUs + " size=" + size
            //    + " flags=" + flags + " startCodeValue=" + Integer.toHexString(startCodeValue)
            //    + " bytesWrittenPastStartCode=" + bytesWrittenPastStartCode);
            sampleIsKeyframe = false;
            firstFieldSampleSize = 0;
            frameCount++;
          } else {
            firstFieldSampleSize = size;
          }
        }
        if (!startedFirstSample || sampleHasPicture) {
          // Start the next sample.
          samplePosition = totalBytesWritten - bytesWrittenPastStartCode;
          sampleTimeUs =
              pesTimeUs != C.TIME_UNSET
                  ? pesTimeUs
                  : (sampleTimeUs != C.TIME_UNSET
                  ? (sampleTimeUs + getFrameDurationUs(pictureHeader.temporalReference))
                  : C.TIME_UNSET);
          if (pesTimeUs != C.TIME_UNSET) {
            sampleTimeBaseUs = pesTimeUs;
          }
          pesTimeUs = C.TIME_UNSET;
          startedFirstSample = true;
        }
        sampleHasPicture = startCodeValue == START_PICTURE;
      } else if (startCodeValue == START_GROUP) {
        sampleIsKeyframe = true;
        fieldCount = 0;
        pictureIndex = 0;
      } else if (startCodeValue == START_USER_DATA) {
        // Log.i("H262", "---===startCodeValue START_USER_DATA");
      } else if (startCodeValue == 0xB7) {
        // Log.i("H262", "---===Sequence End Code");
      } else if (startCodeValue > 0x2f && startCodeValue != START_EXTENSION) {
        Log.i(TAG, "---===startCodeValue not handled: 0x" + Integer.toHexString(startCodeValue));
      }

      offset = startCodeOffset + 3;
    }
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    checkStateNotNull(output); // Asserts that createTracks has been called.
    if (isEndOfInput) {
      @C.BufferFlags int flags = sampleIsKeyframe ? C.BUFFER_FLAG_KEY_FRAME : 0;
      int size = (int) (totalBytesWritten - samplePosition);
      output.sampleMetadata(sampleTimeUs, flags, size, /* offset= */ 0, /* cryptoData= */ null);
    }
  }

  private StringBuilder frameTypesByFieldStringBuilder = new StringBuilder();
  private StringBuilder frameTypesByFrameStringBuilder = new StringBuilder();

  private void debugPrintIPB() {
    if (frameCount < 100) {
      if (pictureHeader != null) {
        int type = pictureHeader.frameType;
        char t = type == FRAME_I ? 'I' : type == FRAME_P ? 'P' : type == FRAME_B ? 'B' : '?';
        frameTypesByFieldStringBuilder.append(t);
        if (isFrameCompleted()) {
          frameTypesByFrameStringBuilder.append(t);
        }
        Log.i("H262Reader", "---===frameType=" + t);
        if (frameCount == 99) {
          Log.i("H262Reader", "---===frameTypes=" + frameTypesByFieldStringBuilder);
          Log.i("H262Reader", "---===frameTypes=" + frameTypesByFrameStringBuilder);
        }
      }
    }
  }

  private long[] getChapterTimesNs(byte[][] initDataBytes) {
    byte[] bytes = initDataBytes != null ? initDataBytes[7] : null;
    if (bytes == null || bytes.length == 0) {
      return null;
    }
    long[] timesUs = new long[bytes.length / 8];
    int p = 0;
    for (int i = 0; i < timesUs.length; i++) {
      timesUs[i] = Longs.fromBytes(
          bytes[p++], bytes[p++], bytes[p++], bytes[p++],
          bytes[p++], bytes[p++], bytes[p++], bytes[p++]);
    }
    return timesUs;
  }

  private SparseIntArray frameDurationFactors = new SparseIntArray(30);
  private int lastPictureRepeatFirstFieldFactor = -1;
  private int lastIndexPictureRepeatFirstFieldFactor = -1;

  private long getFrameDurationFactor(int presentationIndex) {
    int factor = 2;
    int presentationFactor = frameDurationFactors.get(presentationIndex, -1);
    if (presentationFactor != -1) {
      factor = presentationFactor;
    } else if (lastPictureRepeatFirstFieldFactor > 0 &&
        (lastIndexPictureRepeatFirstFieldFactor - presentationIndex) % 2 == 0) {
      factor = lastPictureRepeatFirstFieldFactor;
    }
    //Log.i(TAG, "---===presentationIndex=" + presentationIndex + " factor=" + factor);
    return factor;
  }

  private void updateFrameDurationFactorCache() {
    int factor = 2;
    int presentationIndex = pictureHeader.temporalReference;
    if (pictureHeader != null && pictureHeader.repeatFirstField) {
      // progressive shall be true if repeatFirstField is true
      if (progressiveSequence) {
        factor = pictureHeader.topFieldFirst ? 6 : 4;
      }
      else {
        factor = 3;
      }
      lastPictureRepeatFirstFieldFactor = factor;
      lastIndexPictureRepeatFirstFieldFactor = presentationIndex;
    }

    if (pictureHeader != null) {
      int value = frameDurationFactors.get(presentationIndex, -1);
      if (value != factor) {
        frameDurationFactors.put(presentationIndex, factor);
        if (value != -1) {
          invalidateTimestampOffsets(presentationIndex);
        }
      }
    }
  }

  private long getFrameDurationUs(int presentationIndex) {
    if (frameDurationUs == C.LENGTH_UNSET) {
      return C.LENGTH_UNSET;
    }
    return frameDurationUs * getFrameDurationFactor(presentationIndex) / 2;
  }

  private SparseIntArray frameTimestampOffsetsUs = new SparseIntArray(30);

  private int getTimestampOffsetUs(int presentIndex) {
    int us = frameTimestampOffsetsUs.get(presentIndex, -1);
    if (us >= 0) {
      return us;
    }
    if (presentIndex == 0) {
      us = 0;
    } else {
      us = getTimestampOffsetUs(presentIndex - 1);
      us += getFrameDurationUs(presentIndex - 1);
    }
    frameTimestampOffsetsUs.put(presentIndex, us);
    return us;
  }

  private void invalidateTimestampOffsets(int presentationIndex) {
    List<Integer> keysToRemove = new ArrayList<>();
    for (int i = 0; i < frameTimestampOffsetsUs.size(); i++) {
      int key = frameTimestampOffsetsUs.keyAt(i);
      if (key >= presentationIndex) {
        keysToRemove.add(key);
      }
    }
    for (int key : keysToRemove) {
      frameTimestampOffsetsUs.delete(key);
    }
  }

  private boolean isFrameCompleted() {
    return pictureHeader == null || pictureHeader.pictureStructure == PICTURE_TYPE_FRAME
        || (fieldCount & 0x1) == 0;
  }

  private boolean parseCsdBufferForPictureHeader(CsdBuffer csdBuffer){

    byte[] csdData = Arrays.copyOf(csdBuffer.data, csdBuffer.length);
    if (csdData.length < 7 || csdData[3] != START_PICTURE) {
      return false;
    }

    pictureHeader = new MPEG2PictureHeader();
    pictureHeader.pictureIndex = pictureIndex;
    pictureIndex++;
    int pos = 0;
    boolean havePicExt = false;
    int temp = 0;
    pos += 4;
    temp = (csdData[pos] << 8) | (csdData[pos + 1] & 0xC0);
    pictureHeader.temporalReference = temp >> 6;
    pos += 1;
    temp = (csdData[pos] & 0x38) >> 3 ;
    pictureHeader.frameType = temp & 0xff;

    //Seek to extension
    while (pos < (csdData.length - 4)) {
      if (csdData[pos] == 0x00 && csdData[pos + 1] == 0x00 && csdData[pos + 2] == 0x01
          && (csdData[pos + 3] & 0xff) == START_EXTENSION) {
        if ((csdData[pos + 4] & 0xF0) == 0x80) { //Picture coding extension
          //printf("Found a picture_coding_extension\n");
          havePicExt = true;
          break;
        }
      }
      pos++;
    }
    if (!havePicExt) {
      pictureHeader.pictureStructure = PICTURE_TYPE_FRAME;
      pictureHeader.repeatFirstField = false;
      pictureHeader.topFieldFirst = true;
      pictureHeader.progressive = true;
    } else {
      pos += 4;//skip start code
      pos += 2;//skip f_code shit
      pictureHeader.pictureStructure = (csdData[pos] & 0x03);
      pos++;
      pictureHeader.topFieldFirst = (csdData[pos] & 0x80) > 0;
      pictureHeader.repeatFirstField = (csdData[pos] & 0x02) > 0;
      pos++;
      pictureHeader.progressive = (csdData[pos] & 0x80) > 0;
      fieldCount++;
    }
    updateFrameDurationFactorCache();
    //debugPrintIPB();
    return true;
  }

  /**
   * Parses the {@link Format} and frame duration from a csd buffer.
   *
   * @param csdBuffer The csd buffer.
   * @param formatId The id for the generated format.
   * @return A pair consisting of the {@link Format} and the frame duration in microseconds, or 0 if
   *     the duration could not be determined.
   */
  private static Pair<Format, Pair> parseCsdBuffer(CsdBuffer csdBuffer, String formatId) {
    byte[] csdData = Arrays.copyOf(csdBuffer.data, csdBuffer.length);

    int firstByte = csdData[4] & 0xFF;
    int secondByte = csdData[5] & 0xFF;
    int thirdByte = csdData[6] & 0xFF;
    int width = (firstByte << 4) | (secondByte >> 4);
    int height = (secondByte & 0x0F) << 8 | thirdByte;

    float pixelWidthHeightRatio = 1f;
    int aspectRatioCode = (csdData[7] & 0xF0) >> 4;
    switch (aspectRatioCode) {
      case 2:
        pixelWidthHeightRatio = (4 * height) / (float) (3 * width);
        break;
      case 3:
        pixelWidthHeightRatio = (16 * height) / (float) (9 * width);
        break;
      case 4:
        pixelWidthHeightRatio = (121 * height) / (float) (100 * width);
        break;
      default:
        // Do nothing.
        break;
    }

    Format format =
        new Format.Builder()
            .setId(formatId)
            .setSampleMimeType(MimeTypes.VIDEO_MPEG2)
            .setWidth(width)
            .setHeight(height)
            .setPixelWidthHeightRatio(pixelWidthHeightRatio)
            .setInitializationData(Collections.singletonList(csdData))
            .build();

    long frameDurationUs = 0;
    int frameRateCodeMinusOne = (csdData[7] & 0x0F) - 1;
    if (0 <= frameRateCodeMinusOne && frameRateCodeMinusOne < FRAME_RATE_VALUES.length) {
      double frameRate = FRAME_RATE_VALUES[frameRateCodeMinusOne];
      int sequenceExtensionPosition = csdBuffer.sequenceExtensionPosition;
      int frameRateExtensionN = (csdData[sequenceExtensionPosition + 9] & 0x60) >> 5;
      int frameRateExtensionD = (csdData[sequenceExtensionPosition + 9] & 0x1F);
      if (frameRateExtensionN != frameRateExtensionD) {
        frameRate *= (frameRateExtensionN + 1d) / (frameRateExtensionD + 1);
      }
      frameDurationUs = (long) (C.MICROS_PER_SECOND / frameRate);
      format = format.buildUpon().setFrameRate((float) frameRate).build();
    }

    //Seek to extension
    int pos = 8;
    boolean progressiveSequence = false;
    while (pos < (csdData.length - 6)) {
      if (csdData[pos] == 0x00 && csdData[pos + 1] == 0x00 && csdData[pos + 2] == 0x01
          && (csdData[pos + 3] & 0xff) == START_EXTENSION) {
        if((csdData[pos + 4] & 0xF0) == 0x10){ // Sequence extension
          // profileLevelIndication = ((data[pos +4] & 0x0F) << 4) | ((data[pos + 5] & 0xF0) >> 4);
          progressiveSequence = ((csdData[pos +5] & 0x08) >> 3) > 0;
          break;
        }
      }
      pos++;
    }

    return Pair.create(format, Pair.create(frameDurationUs, progressiveSequence));
  }

  private static final class CsdBuffer {

    private static final byte[] START_CODE = new byte[] {0, 0, 1};

    private boolean isFilling;
    private int codeFilling = -1;

    public int length;
    public int sequenceExtensionPosition;
    public byte[] data;

    public CsdBuffer(int initialCapacity) {
      data = new byte[initialCapacity];
    }

    /** Resets the buffer, clearing any data that it holds. */
    public void reset() {
      codeFilling = -1;
      isFilling = false;
      length = 0;
      sequenceExtensionPosition = 0;
    }

    /**
     * Called when a start code is encountered in the stream.
     *
     * @param startCodeValue The start code value.
     * @param bytesAlreadyPassed The number of bytes of the start code that have been passed to
     *     {@link #onData(byte[], int, int)}, or 0.
     * @return Whether the csd data is now complete. If true is returned, neither this method nor
     *     {@link #onData(byte[], int, int)} should be called again without an interleaving call to
     *     {@link #reset()}.
     */
    public boolean onStartCode(int startCodeValue, int bytesAlreadyPassed) {
      if (isFilling) {
        if (codeFilling == START_PICTURE) {
          length -= bytesAlreadyPassed;
          if (sequenceExtensionPosition == 0 && startCodeValue == START_EXTENSION) {
            sequenceExtensionPosition = length;
            onData(START_CODE, 0, START_CODE.length);
            return false;
          } else {
            isFilling = false;
            return true;
          }
        }
        length -= bytesAlreadyPassed;
        if (sequenceExtensionPosition == 0 && startCodeValue == START_EXTENSION) {
          sequenceExtensionPosition = length;
        } else {
          isFilling = false;
          return true;
        }
      } else if (startCodeValue == START_SEQUENCE_HEADER || startCodeValue == START_PICTURE) {
        reset();
        codeFilling = startCodeValue;
        isFilling = true;
      }
      onData(START_CODE, 0, START_CODE.length);
      return false;
    }

    /**
     * Called to pass stream data.
     *
     * @param newData Holds the data being passed.
     * @param offset The offset of the data in {@code data}.
     * @param limit The limit (exclusive) of the data in {@code data}.
     */
    public void onData(byte[] newData, int offset, int limit) {
      if (!isFilling) {
        return;
      }
      int readLength = limit - offset;
      if (data.length < length + readLength) {
        data = Arrays.copyOf(data, (length + readLength) * 2);
      }
      System.arraycopy(newData, offset, data, length, readLength);
      length += readLength;
    }
  }

  private static class MPEG2PictureHeader {
    int pictureIndex;
    int temporalReference;
    int frameType;
    int pictureStructure;
    boolean repeatFirstField;
    boolean topFieldFirst;
    boolean progressive;
  };
}
