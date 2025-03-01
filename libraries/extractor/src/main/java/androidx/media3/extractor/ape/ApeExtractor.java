/*
 * Copyright (C) 2025 The Android Open Source Project
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
package androidx.media3.extractor.ape;

import static androidx.media3.common.util.Util.binarySearchFloor;
import static androidx.media3.common.util.Util.getPcmEncoding;
import static androidx.media3.extractor.ape.ApeUtil.getSamplesAtTimeUs;
import static androidx.media3.extractor.ape.ApeUtil.getTimeUs;

import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;

public final class ApeExtractor implements Extractor {

  private static final String TAG = ApeExtractor.class.getSimpleName();
  private static final int STATE_READ_HEADER = 0;
  private static final int STATE_READ_FRAMES = 1;
  private static final int FFMPEG_FRAME_HEADER_LENGTH = 8;

  private @MonotonicNonNull ParsableByteArray buffer;
  private @MonotonicNonNull ExtractorOutput extractorOutput;
  private @MonotonicNonNull TrackOutput trackOutput;

  private @MonotonicNonNull ApeInfo apeInfo;
  private ApeFrame[] frames;
  int currentFrame;
  private long[] frameSamplesAddUp;
  private long[] framePositions;
  private int state;

  public ApeExtractor() {
    state = STATE_READ_HEADER;
    frames = new ApeFrame[0];
    framePositions = new long[0];
    frameSamplesAddUp = new long[0];
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    return ApeHeaderReader.checkFileType(input);
  }

  @Override
  public void init(ExtractorOutput output) {
    extractorOutput = output;
    trackOutput = output.track(/* id= */ 0, C.TRACK_TYPE_AUDIO);
    output.endTracks();
  }

  @Override
  public void release() {
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    switch (state) {
      case STATE_READ_HEADER:
        input.resetPeekPosition();
        apeInfo = ApeHeaderReader.read(input);
        processSeekTable(apeInfo, input);
        setupFormat(apeInfo, input.getLength());
        buildSeekMap();
        if (apeInfo.totalFrames > 0) {
          int bytesSkip = (int) (frames[0].pos - input.getPosition());
          input.skipFully(bytesSkip);
          state = STATE_READ_FRAMES;
          currentFrame = 0;
          return Extractor.RESULT_CONTINUE;
        } else {
          return Extractor.RESULT_END_OF_INPUT;
        }
      case STATE_READ_FRAMES:
        input.resetPeekPosition();
        return readFrames(input, seekPosition);
      default:
        throw new IllegalStateException();
    }
  }

  public void seek(long position, long timeUs) {
    if (position == 0) {
      state = STATE_READ_HEADER;
    } else {
      long samples = getSamplesAtTimeUs(timeUs, apeInfo.sampleRate, apeInfo.totalSamples);
      currentFrame = binarySearchFloor(frameSamplesAddUp, samples, true, true);
    }
  }

  // 4-byte data align in little endian order causes frames overlapping in storage by 4 bytes
  private long cachedPosition = C.INDEX_UNSET;
  private final byte[] cachedBytes = new byte[4];

  private int readFrames(ExtractorInput input, PositionHolder seekPosition)
      throws IOException {

    if (currentFrame < 0) {
      currentFrame = 0;
    }

    if (currentFrame >= apeInfo.totalFrames) {
      return RESULT_END_OF_INPUT;
    }

    boolean cacheHit = false;
    long inputPosition = input.getPosition();
    long goalStart = frames[currentFrame].pos;
    if (goalStart != inputPosition) {
      cacheHit = goalStart == cachedPosition &&
          inputPosition <= cachedPosition + cachedBytes.length;
      if (!cacheHit) {
        // frame seeking should be taken care of by seek map, should no seeking here
        Log.w(TAG, "readFrames(): unexpected seeking "
            + (goalStart - inputPosition) + " bytes for frame " + currentFrame);
        seekPosition.position = goalStart;
        return RESULT_SEEK;
      }
    }

    int bufferSize = frames[currentFrame].size + FFMPEG_FRAME_HEADER_LENGTH;
    getBufferReady(bufferSize);

    byte[] header = createFfmpegFrameHeader(currentFrame);
    System.arraycopy(header, 0, buffer.getData(), 0, header.length);
    int bytesBuffered = header.length;
    buffer.setPosition(bytesBuffered);

    if (cacheHit) {
      int bytesToCopy = (int) (inputPosition - goalStart);
      System.arraycopy(cachedBytes, 0, buffer.getData(), bytesBuffered, bytesToCopy);
      bytesBuffered += bytesToCopy;
    }

    while (bytesBuffered < bufferSize) {
      int bytesRead = input.read(buffer.getData(), bytesBuffered, bufferSize - bytesBuffered);
      if (bytesRead == C.RESULT_END_OF_INPUT) {
        return RESULT_END_OF_INPUT;
      }
      bytesBuffered += bytesRead;
    }

    cachedPosition = input.getPosition() - cachedBytes.length;
    System.arraycopy(
        buffer.getData(), bufferSize - cachedBytes.length,
        cachedBytes, 0, cachedBytes.length);

    buffer.setPosition(0);
    trackOutput.sampleData(buffer, bufferSize);
    outputSampleMetadata(apeInfo, currentFrame, bufferSize);
    currentFrame++;

    return currentFrame == apeInfo.totalFrames ? RESULT_END_OF_INPUT
        : RESULT_CONTINUE;
  }

  private void setupFormat(ApeInfo info, long fileLength) {
    long duration = info.durationUs / C.MICROS_PER_SECOND;
    int averageBitrate = (duration <= 0) ? 0 : (int) ((fileLength * 8L) / duration);

    Format format = new Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_APE)
        .setCodecs("ape")
        .setId(null)
        .setAverageBitrate(averageBitrate)
        .setSampleRate((int) info.sampleRate)
        .setChannelCount(info.channels)
        .setInitializationData(Collections.singletonList(buildDecoderConfigExtraData(info)))
        .setMaxInputSize(Format.NO_VALUE)
        .setMetadata(null)
        .setPcmEncoding(getPcmEncoding(info.bitsPerSample))
        .build();
    trackOutput.format(format);
  }

  private void outputSampleMetadata(ApeInfo info, int frameIndex, int size) {
    long timeUs = frameIndex == 0 ? 0
        : getTimeUs((long) frameIndex * info.blocksPerFrame, info.sampleRate);
    trackOutput
        .sampleMetadata(
            timeUs,
            C.BUFFER_FLAG_KEY_FRAME,
            size,
            /* offset= */ 0,
            /* cryptoData= */ null);
  }

  // seek table processing logic from ape_read_header() in FFmpeg/libavformat/ape.c
  private void processSeekTable(ApeInfo info, ExtractorInput input)
      throws IOException {

    if (info.seekTableLength / 4 < info.totalFrames) {
      // Number of seek entries is less than number of frames
      return;
    }

    long junkLength = 0;

    long firstFramePosition = junkLength
        + info.descriptorLength
        + info.headerLength
        + info.seekTableLength
        + info.wavHeaderLength;
    if (info.fileVersion < 3810) {
      firstFramePosition += info.totalFrames;
    }

    frames = new ApeFrame[info.totalFrames];

    frames[0] = new ApeFrame();
    frames[0].pos = firstFramePosition;
    frames[0].blocks = info.blocksPerFrame;
    frames[0].skip = 0;

    // read seek table 4-bytes elements one by one
    int byteSize = 4;
    ParsableByteArray scratch = new ParsableByteArray(byteSize);
    // first element in seek table should be equal with firstFramePosition
    input.peekFully(scratch.getData(), 0, byteSize);
    long position = scratch.readLittleEndianUnsignedInt();
    Assertions.checkState(position == firstFramePosition);
    for (int i = 1; i < info.totalFrames; i++) {
      input.peekFully(scratch.getData(), 0, byteSize);
      scratch.setPosition(0);
      long seekTableEntry = scratch.readLittleEndianUnsignedInt();
      frames[i] = new ApeFrame();
      frames[i].pos = seekTableEntry + junkLength;
      frames[i].blocks = info.blocksPerFrame;
      frames[i - 1].size = (int) (frames[i].pos - frames[i - 1].pos);
      frames[i].skip = ((int) (frames[i].pos - frames[0].pos)) & 3;
    }
    // skip rest elements in seek table which are useless
    input.advancePeekPosition((int) (info.seekTableLength / 4) - info.totalFrames);

    frames[info.totalFrames - 1].blocks = info.finalFrameBlocks;
    {
      /* calculate final packet size from total file size, if available */
      long file_size = input.getLength();
      long final_size = 0;
      if (file_size > 0) {
        final_size = file_size - frames[info.totalFrames - 1].pos - info.wavTailLength;
        final_size -= final_size & 3;
      }
      if (file_size <= 0 || final_size <= 0) {
        final_size = info.finalFrameBlocks * 8L;
      }
      frames[info.totalFrames - 1].size = (int) final_size;
    }

    for (int i = 0; i < info.totalFrames; i++) {
      if (frames[i].skip > 0){
        frames[i].pos -= frames[i].skip;
        frames[i].size += frames[i].skip;
      }
      frames[i].size = (frames[i].size + 3) & ~3;
    }
    if (info.fileVersion < 3810) {
      // read 2-bytes elements one by one
      for (int i = 0; i < info.totalFrames; i++) {
        input.peekFully(scratch.getData(), 0, 2);
        scratch.setPosition(0);
        int bits = scratch.readLittleEndianUnsignedShort();
        if (i > 0 && bits != 0) {
          frames[i - 1].size += 4;
        }
        frames[i].skip <<= 3;
        frames[i].skip += bits;
      }
    }
    long pts = 0;
    for (int i = 0; i < info.totalFrames; i++) {
      frames[i].pts = pts;
      pts += info.blocksPerFrame;
    }

    this.frameSamplesAddUp = new long[frames.length];
    for (int i = 0; i < frames.length; i++) {
      frameSamplesAddUp[i] = frames[i].pts;
    }

    framePositions = new long[frames.length];
    for (int i = 0; i < frames.length; i++) {
      framePositions[i] = frames[i].pos;
    }
  }

  private void buildSeekMap() {
    SeekMap seekMap =
        new ApeSeekTableSeekMap(apeInfo, framePositions, frameSamplesAddUp);
    extractorOutput.seekMap(seekMap);
  }

  /*
  The extra data is for FFMpeg APE decoder config.
  Put into Format initializationData list, pass to FfmpegAudioDecoder.
  Here are code lines from apedec.c:
  s->fileversion       = AV_RL16(avctx->extradata);
  s->compression_level = AV_RL16(avctx->extradata + 2);
  s->flags             = AV_RL16(avctx->extradata + 4);
   */
  private byte[] buildDecoderConfigExtraData(ApeInfo info) {
    return ByteBuffer
        .allocate(6)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort((short) info.fileVersion)
        .putShort((short) info.compressionType)
        .putShort((short) info.formatFlags)
        .array();
  }

  /*
  The frame header is defined in ape_decode_frame() in apedec.c
   */
  private byte[] createFfmpegFrameHeader(int frameIndex) {
    return ByteBuffer.allocate(FFMPEG_FRAME_HEADER_LENGTH)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(frames[frameIndex].blocks) // nblocks
        .putInt(frames[frameIndex].skip) // offset
        .array();
  }

  private void getBufferReady(int bufferSize) {
    if (buffer == null) {
      int capacity = getLargestFrameSize() + FFMPEG_FRAME_HEADER_LENGTH;
      buffer = new ParsableByteArray(capacity);
    }
    Assertions.checkArgument(buffer.capacity() >= bufferSize);
  }

  private int getLargestFrameSize() {
    int largestFrameSize = 0;
    for (ApeFrame frame : frames) {
      if (frame.size > largestFrameSize) {
        largestFrameSize = frame.size;
      }
    }
    return largestFrameSize;
  }

  private static class ApeFrame {
    long pos;
    int size;
    int blocks;
    int skip;
    long pts;
  }
}
