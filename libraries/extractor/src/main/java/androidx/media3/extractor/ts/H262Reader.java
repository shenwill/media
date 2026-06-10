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

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
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
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Stack;

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

  private @MonotonicNonNull String formatId;
  private @MonotonicNonNull TrackOutput output;
  private @MonotonicNonNull ExtractorOutput extractorOutput;

  // Maps (frame_rate_code - 1) indices to values, as defined in ITU-T H.262 Table 6-4.
  private static final double[] FRAME_RATE_VALUES =
      new double[]{24000d / 1001, 24, 25, 30000d / 1001, 30, 50, 60000d / 1001, 60};

  private final RunningStatus runningStatus = new RunningStatus();
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
  private int csdFrameDurationUs;

  // Per packet state that gets reset at the start of each packet.
  private long pesTimeUs;

  // Per sample state that gets reset at the start of each sample.
  private long samplePosition;
  private long roughSampleTimeUs;
  private boolean sampleIsKeyframe;
  private boolean sampleHasPicture;

  // per every picture
  @Nullable private MPEG2PictureHeader pictureHeader;

  /* package */ H262Reader(@Nullable UserDataReader userDataReader, byte[][] initDataBytes) {
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
    csdFrameDurationUs = C.LENGTH_UNSET;
    pesTimeUs = C.TIME_UNSET;
    roughSampleTimeUs = C.TIME_UNSET;
  }

  private static int calculatePictureDuration(
      int csdFrameDuration, boolean csdProgressiveSequence, MPEG2PictureHeader header) {
    int pictureDuration;
    if (csdProgressiveSequence) {
      // --- PURE PROGRESSIVE MODE ---
      if (!header.repeatFirstField) {
        pictureDuration = csdFrameDuration;      // 1x Frame
      } else if (!header.topFieldFirst) {
        pictureDuration = csdFrameDuration * 2;  // 2x Frame
      } else {
        pictureDuration = csdFrameDuration * 3;  // 3x Frame
      }
    } else {
      int singleFieldDuration = csdFrameDuration / 2;
      // --- INTERLACED / FIELD MODE ---
      if (header.pictureStructure == PICTURE_TYPE_TOP_FIELD
          || header.pictureStructure == PICTURE_TYPE_BOTTOM_FIELD) {
        pictureDuration = singleFieldDuration;    // 1 Field Picture
      } else {
        // Full Frame Picture (Standard or 3:2 Pulldown)
        pictureDuration = (header.progressive && header.repeatFirstField) ?
            (singleFieldDuration * 3) : (singleFieldDuration * 2);
      }
    }
    return pictureDuration;
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
    roughSampleTimeUs = C.TIME_UNSET;
    runningStatus.onSeek();
    sampleHasPicture = false;
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
    if (pesTimeUs != C.TIME_UNSET) {
      roughSampleTimeUs = pesTimeUs;
    }
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
          pictureHeader = parseBufferForPictureHeader(csdBuffer);
          if (pictureHeader != null) {
            runningStatus.addPicture(pictureHeader, pesTimeUs);
            pesTimeUs = C.TIME_UNSET;
          }
        }
        if (!hasOutputFormat && completed && csdBuffer.codeFilling == START_SEQUENCE_HEADER) {
          // The csd data is complete, so we can decode and output the media format.
          Pair<Format, Pair<Integer, Boolean>> result = parseCsdBuffer(
              csdBuffer, checkNotNull(formatId));
          output.format(result.first);
          if (chapterTimesNs != null) {
            extractorOutput.chapters(chapterTimesNs, null);
          }
          csdFrameDurationUs = result.second.first;
          runningStatus.setCsdData(csdFrameDurationUs, result.second.second);
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
          Util.castNonNull(userDataReader).consume(roughSampleTimeUs, userDataParsable);
        }

        if (startCodeValue == START_USER_DATA && data.getData()[startCodeOffset + 2] == 0x1) {
          userData.startNalUnit(startCodeValue);
        }
      }
      if (startCodeValue == START_PICTURE || startCodeValue == START_SEQUENCE_HEADER) {
        int bytesWrittenPastStartCode = limit - startCodeOffset;
        if (sampleHasPicture && hasOutputFormat && roughSampleTimeUs != C.TIME_UNSET) {
          // Output the sample.
          @C.BufferFlags int flags = sampleIsKeyframe ? C.BUFFER_FLAG_KEY_FRAME : 0;
          int size = (int) (totalBytesWritten - samplePosition) - bytesWrittenPastStartCode;
          runningStatus.onPictureDataReady(flags, size, bytesWrittenPastStartCode, output);
          roughSampleTimeUs += pictureHeader != null ? pictureHeader.pictureDurationUs : 0;
          sampleIsKeyframe = false;
        }
        if (!startedFirstSample || sampleHasPicture) {
          // Start the next sample.
          samplePosition = totalBytesWritten - bytesWrittenPastStartCode;
          startedFirstSample = true;
        }
        sampleHasPicture = startCodeValue == START_PICTURE;
      } else if (startCodeValue == START_GROUP) {
        runningStatus.onGopStart();
        sampleIsKeyframe = true;
      } else if (startCodeValue == START_USER_DATA) {
        // Log.i(TAG, "---===startCodeValue START_USER_DATA");
      } else if (startCodeValue == 0xB7) {
        // Log.i(TAG, "---===Sequence End Code");
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
      output.sampleMetadata(roughSampleTimeUs, flags, size, /* offset= */ 0, /* cryptoData= */ null);
    }
  }

  @Nullable
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

  // PictureHeader is not part of CSD (Codec-Specific Data) which is initialization metadata
  // sent once to initialize decoder configuration before sending the actual video frames.
  // PictureHeader exist for every decoded image
  @Nullable
  private MPEG2PictureHeader parseBufferForPictureHeader(CsdBuffer csdBuffer){

    byte[] csdData = Arrays.copyOf(csdBuffer.data, csdBuffer.length);
    // I-frame Picture Header has a minimum bit length of 62
    if (csdData.length < 8) {
      Assertions.checkState(false, "Picture Header length is " + csdData.length);
      return null;
    }
    if (csdData[3] != START_PICTURE) {
      Assertions.checkState(false, "Picture Header 0x00 not 0x" + Integer.toHexString(csdData[3]));
      return null;
    }

    int pos = 0;
    boolean havePicExt = false;
    pos += 4;
    int temp = (csdData[pos] << 8) | (csdData[pos + 1] & 0xC0);
    int temporalReference = temp >> 6;
    pos += 1;
    temp = (csdData[pos] & 0x38) >> 3 ;
    int frameType = temp & 0xff;

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
    int pictureStructure = PICTURE_TYPE_FRAME;
    boolean repeatFirstField = false, topFieldFirst = true, progressive = true;
    if (havePicExt) {
      pos += 4;//skip start code
      pos += 2;//skip f_code shit
      pictureStructure = (csdData[pos] & 0x03);
      pos++;
      topFieldFirst = (csdData[pos] & 0x80) > 0;
      repeatFirstField = (csdData[pos] & 0x02) > 0;
      pos++;
      progressive = (csdData[pos] & 0x80) > 0;
    }
    return runningStatus.obtainPictureHeader().init(
        temporalReference, frameType, pictureStructure,
        repeatFirstField, topFieldFirst, progressive,
        csdFrameDurationUs, runningStatus.csdProgressiveSequence);
  }

  /**
   * Parses the {@link Format} and frame duration from a csd buffer.
   *
   * @param csdBuffer The csd buffer.
   * @param formatId The id for the generated format.
   * @return A pair consisting of the {@link Format} and the frame duration in microseconds, or 0 if
   *     the duration could not be determined.
   */
  private static Pair<Format, Pair<Integer, Boolean>> parseCsdBuffer(
      CsdBuffer csdBuffer, String formatId) {

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

    int frameDurationUs = 0;
    int frameRateCodeMinusOne = (csdData[7] & 0x0F) - 1;
    if (0 <= frameRateCodeMinusOne && frameRateCodeMinusOne < FRAME_RATE_VALUES.length) {
      double frameRate = FRAME_RATE_VALUES[frameRateCodeMinusOne];
      int sequenceExtensionPosition = csdBuffer.sequenceExtensionPosition;
      int frameRateExtensionN = (csdData[sequenceExtensionPosition + 9] & 0x60) >> 5;
      int frameRateExtensionD = (csdData[sequenceExtensionPosition + 9] & 0x1F);
      if (frameRateExtensionN != frameRateExtensionD) {
        frameRate *= (frameRateExtensionN + 1d) / (frameRateExtensionD + 1);
      }
      frameDurationUs = (int) (C.MICROS_PER_SECOND / frameRate);
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
          progressiveSequence = ((csdData[pos + 5] & 0x08) >> 3) > 0;
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
    int temporalReference;
    int frameType;
    int pictureStructure;
    boolean repeatFirstField;
    boolean topFieldFirst;
    boolean progressive;
    int fieldIndex;
    int gopSN;
    int pictureDurationUs;
    int presentationIndices;

    public MPEG2PictureHeader init(
        int temporalReference, int frameType, int pictureStructure,
        boolean repeatFirstField, boolean topFieldFirst, boolean progressive,
        int csdFrameDuration, boolean csdProgressiveSequence) {
      this.temporalReference = temporalReference;
      this.frameType = frameType;
      this.pictureStructure = pictureStructure;
      this.repeatFirstField = repeatFirstField;
      this.topFieldFirst = topFieldFirst;
      this.progressive = progressive;
      this.fieldIndex = (pictureStructure == PICTURE_TYPE_BOTTOM_FIELD && topFieldFirst)
          || ((pictureStructure == PICTURE_TYPE_TOP_FIELD && !topFieldFirst)) ? 1 : 0;
      this.pictureDurationUs
          = calculatePictureDuration(csdFrameDuration, csdProgressiveSequence, this);
      return this;
    }

    public void reset() {
    }

    public void setGopSN(int gopSN) {
      this.gopSN = gopSN;
      this.presentationIndices = gopSN << 11 | temporalReference << 1 | fieldIndex;
    }

    @Override
    public String toString() {
      return "GOP=" + gopSN + " TR=" + temporalReference
          + " pIndices=0x" + Integer.toHexString(presentationIndices)
          + " durationUs=" + pictureDurationUs
          + " " + (frameType == 1 ? 'I' : frameType == 2 ? 'P' : frameType == 3 ? 'B' : '?')
          + " " + (pictureStructure == PICTURE_TYPE_BOTTOM_FIELD ? "BOTTOM"
              : pictureStructure == PICTURE_TYPE_TOP_FIELD ? "TOP"
              : pictureStructure == PICTURE_TYPE_FRAME ? "FRAME" : "UNKNOWN")
          + " progressive="+ (progressive ? "Y" : "N")
          + " repeatFF=" + (repeatFirstField ? "Y" : "N")
          + " topFF=" + (topFieldFirst ? "Y" : "N");
    }
  }

  private static final class RunningStatus {

    // Ultimate T2 DVD runs about 20 items in queue, based on GOP size
    static final int POOL_SIZE = 40;

    int csdFrameDurationUs = C.LENGTH_UNSET;
    boolean csdProgressiveSequence;
    int gopSN = 0;
    @Nullable MPEG2PictureHeader newPictureHeader;
    final MPEG2PictureHeaderPool pictureHeaderPool = new MPEG2PictureHeaderPool(POOL_SIZE);
    LinkedList<PictureRunningInfo> pendingDecodingPicturesQueue = new LinkedList<>();
    int pesTimeGopSN = C.INDEX_UNSET;
    int pesTimeTR = C.INDEX_UNSET;
    long pesTimeUs = C.TIME_UNSET;
    final PictureRunningInfoPool pictureRunningInfoPool = new PictureRunningInfoPool(POOL_SIZE);
    int totalPendingPictureSizeInQueue = 0;

    public void setCsdData(int durationUs, boolean progressiveSequence) {
      this.csdFrameDurationUs = durationUs;
      this.csdProgressiveSequence = progressiveSequence;
    }

    public void addPicture(MPEG2PictureHeader pictureHeader, long pesTimeUs) {
      newPictureHeader = pictureHeader;
      newPictureHeader.setGopSN(gopSN);
      if (pesTimeUs != C.TIME_UNSET) {
        this.pesTimeGopSN = gopSN;
        this.pesTimeTR = pictureHeader.temporalReference;
        this.pesTimeUs = pesTimeUs;
      }
    }

    public MPEG2PictureHeader obtainPictureHeader() {
      return pictureHeaderPool.obtain();
    }

    public void onGopStart() {
      gopSN++;
    }

    public void onPictureDataReady(
        int flags, int pictureSize, int bytesWrittenPastStartCode,
        TrackOutput trackOutput) {
      Assertions.checkState(newPictureHeader != null, "newPictureHeader should not be null");
      MPEG2PictureHeader pictureHeader = newPictureHeader;
      PictureRunningInfo pictureRunningInfo = pictureRunningInfoPool.obtain();
      pictureRunningInfo.init(flags, pictureHeader, pictureSize);
      if (pictureHeader.gopSN == pesTimeGopSN && pictureHeader.temporalReference == pesTimeTR) {
        pictureRunningInfo.setPts(pesTimeUs);
      }
      pendingDecodingPicturesQueue.offer(pictureRunningInfo);
      totalPendingPictureSizeInQueue += pictureRunningInfo.pictureSize;
      outputPendingPictures(bytesWrittenPastStartCode, trackOutput);
    }

    public void onSeek() {
      reset();
    }

    public void reset() {
      csdFrameDurationUs = C.LENGTH_UNSET;
      csdProgressiveSequence = false;
      gopSN = 0;
      newPictureHeader = null;
      pesTimeGopSN = C.INDEX_UNSET;
      pesTimeTR = C.INDEX_UNSET;
      pesTimeUs = C.TIME_UNSET;
      pendingDecodingPicturesQueue.clear();
      totalPendingPictureSizeInQueue = 0;
      timeUs = 0;
    }

    @Nullable
    private PictureRunningInfo getFirstPictureToOutput(List<PictureRunningInfo> list) {
      for (PictureRunningInfo pictureRunningInfo : list) {
        if (!pictureRunningInfo.used) {
          return pictureRunningInfo;
        }
      }
      return null;
    }

    private void outputPendingPictures(int bytesWrittenPastStartCode, TrackOutput trackOutput) {
      while (!pendingDecodingPicturesQueue.isEmpty()) {
        List<PictureRunningInfo> presentationSorted = new ArrayList<>(pendingDecodingPicturesQueue);
        presentationSorted.sort(Comparator.comparingInt(p -> p.pictureHeader.presentationIndices));
        populatePts(presentationSorted);
        PictureRunningInfo firstPicture = getFirstPictureToOutput(pendingDecodingPicturesQueue);
        if (firstPicture != null && firstPicture.ptsUs != C.TIME_UNSET) {
          totalPendingPictureSizeInQueue -= firstPicture.pictureSize;
          int offset = bytesWrittenPastStartCode + totalPendingPictureSizeInQueue;
          trackOutput.sampleMetadata(
              firstPicture.ptsUs, firstPicture.flags, firstPicture.pictureSize, offset, null);
          timeUs = firstPicture.ptsUs;
          firstPicture.used = true;
          shrinkQueue(pendingDecodingPicturesQueue);
        } else {
          break;
        }
      }
    }
    long timeUs = 0;

    private void shrinkQueue(LinkedList<PictureRunningInfo> pendingDecodingPicturesQueue) {
      while (true) {
        PictureRunningInfo pictureRunningInfo = pendingDecodingPicturesQueue.peek();
        if (pictureRunningInfo != null && pictureRunningInfo.used
            && pictureRunningInfo.pictureHeader.gopSN < gopSN) {
          pendingDecodingPicturesQueue.poll();
          pictureHeaderPool.recycle(pictureRunningInfo.pictureHeader);
          pictureRunningInfoPool.recycle(pictureRunningInfo);
        } else {
          break;
        }
      }
    }

    private boolean considerConsecutive(int index, int listSize, int indices1, int indices2) {
      indices1 &= 0x7fffffff;
      indices2 &= 0x7fffffff;
      if (indices2 - indices1 == 0b10) {
        return true;
      }
      if (indices2 > indices1 && (indices2 & 0x000007fe) == 0) {
        return true;
      }
      // error handler: something wrong and do not block
      return listSize - index > 18;
    }

    private int getMaxIndexForContinuity(List<PictureRunningInfo> presentationSortedList) {
      int len = presentationSortedList.size();
      if (len <= 1) {
        return len - 1;
      }
      int maxIndexForContinuity = 0;
      for (int i = 1; i < len; i++) {
        if (considerConsecutive(
            i, len,
            presentationSortedList.get(i - 1).pictureHeader.presentationIndices,
            presentationSortedList.get(i).pictureHeader.presentationIndices)) {
          maxIndexForContinuity = i;
        } else {
          break;
        }
      }
      return maxIndexForContinuity;
    }

    private void populatePts(List<PictureRunningInfo> presentationSortedList) {
      int firstIndexNeedSolving = -1;
      int indexCanHelpSolving = -1;
      int maxIndexContinuity = getMaxIndexForContinuity(presentationSortedList);
      for (int i = 0; i <= maxIndexContinuity; i++) {
        PictureRunningInfo pictureRunningInfo = presentationSortedList.get(i);
        boolean needSolve = pictureRunningInfo.ptsUs == C.TIME_UNSET;
        if (needSolve) {
          if (firstIndexNeedSolving == -1) {
            firstIndexNeedSolving = i;
          }
        } else {
          indexCanHelpSolving = i;
        }
        if (firstIndexNeedSolving != -1 && indexCanHelpSolving != -1) {
          break;
        }
      }
      if (firstIndexNeedSolving != -1 && indexCanHelpSolving != -1) {
        long ptsUs = presentationSortedList.get(indexCanHelpSolving).ptsUs;
        if (firstIndexNeedSolving > indexCanHelpSolving) {
          for (int i = indexCanHelpSolving + 1; i <= maxIndexContinuity; i++) {
            PictureRunningInfo pictureRunningInfo = presentationSortedList.get(i);
            if (pictureRunningInfo.ptsUs == C.TIME_UNSET) {
              ptsUs += presentationSortedList.get(i - 1).pictureHeader.pictureDurationUs;
              pictureRunningInfo.ptsUs = ptsUs;
            } else {
              ptsUs = pictureRunningInfo.ptsUs;
            }
          }
        } else {
          for (int i = indexCanHelpSolving - 1; i >= 0; i--) {
            PictureRunningInfo pictureRunningInfo = presentationSortedList.get(i);
            if (pictureRunningInfo.ptsUs == C.TIME_UNSET) {
              ptsUs -= presentationSortedList.get(i).pictureHeader.pictureDurationUs;
              pictureRunningInfo.ptsUs = ptsUs;
            } else {
              ptsUs = pictureRunningInfo.ptsUs;
            }
          }
        }
      }
    }
  }

  private static final class PictureRunningInfo {

    int flags;
    @Nullable MPEG2PictureHeader pictureHeader = null;
    int pictureSize = 0;
    long ptsUs = C.TIME_UNSET;
    boolean used;

    public void reset() {
      flags = 0;
      pictureHeader = null;
      pictureSize = 0;
      ptsUs = C.TIME_UNSET;
      used = false;
    }

    public void init(int flags, MPEG2PictureHeader pictureHeader, int pictureSize) {
      this.flags = flags;
      this.pictureHeader = pictureHeader;
      this.pictureSize = pictureSize;
    }

    public void setPts(long us) {
      this.ptsUs = us;
    }
  }

  private static final class MPEG2PictureHeaderPool
      extends AbstractObjectPool<MPEG2PictureHeader> {
    public MPEG2PictureHeaderPool(int maxCapacity) {
      super(maxCapacity);
    }

    @Override
    protected MPEG2PictureHeader createInstance() {
      return new MPEG2PictureHeader();
    }

    @Override
    protected void resetInstance(MPEG2PictureHeader instance) {
      instance.reset();
    }
  }

  private static final class PictureRunningInfoPool
      extends AbstractObjectPool<PictureRunningInfo> {
    public PictureRunningInfoPool(int maxCapacity) {
      super(maxCapacity);
    }

    @Override
    protected PictureRunningInfo createInstance() {
      return new PictureRunningInfo();
    }

    @Override
    protected void resetInstance(PictureRunningInfo instance) {
      instance.reset();
    }
  }

  private static abstract class AbstractObjectPool<T> {
    public final int maxCapacity;
    private final Stack<T> pool = new Stack<>();

    public AbstractObjectPool(int maxCapacity) {
      this.maxCapacity = maxCapacity;
    }

    protected abstract T createInstance();

    protected abstract void resetInstance(T instance);

    public synchronized T obtain() {
      if (!pool.isEmpty()) {
        return pool.pop();
      }
      return createInstance();
    }

    public synchronized void recycle(T instance) {
      if (instance == null) return;

      resetInstance(instance);

      if (pool.size() < maxCapacity) {
        pool.push(instance);
      }
    }
  }
}
