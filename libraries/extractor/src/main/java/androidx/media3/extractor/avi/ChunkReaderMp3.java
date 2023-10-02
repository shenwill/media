/*
 * Copyright 2022 The Android Open Source Project
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
package androidx.media3.extractor.avi;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.MpegAudioUtil;
import androidx.media3.extractor.TrackOutput;

import java.io.IOException;

/** Reads MP3 audio chunks holding sample data.
  * Credit to https://github.com/dburckh/Media3Avi */
/* package */ final class ChunkReaderMp3 extends ChunkReader {

  private static final int SAMPLES_PER_FRAME_L3_V1 = 1152;
  private final MpegAudioUtil.Header header = new MpegAudioUtil.Header();
  private final ParsableByteArray scratch = new ParsableByteArray(8);
  private final int samplesPerSecond;
  /**
   *  Bytes remaining in the Mpeg Audio frame
   *  This includes bytes in both the scratch buffer and the stream
   *  0 means we are seeking a new frame
   */
  private int frameRemaining = 0;

  /**
   * Current time in the stream
   */
  private long timeUs;

  public ChunkReaderMp3(int id, long durationUs, int samplesPerSecond,
                        int streamHeaderChunkCount, TrackOutput trackOutput) {
    super(
        id, getChunkIdFourCc(id, CHUNK_TYPE_AUDIO), /* alternativeChunkId */ -1,
        durationUs, streamHeaderChunkCount, trackOutput);
    this.samplesPerSecond = samplesPerSecond;
    header.samplesPerFrame = SAMPLES_PER_FRAME_L3_V1;
  }

  @Override
  protected void invalidateCurrentChunkPosition() {
    timeUs = C.TIME_UNSET;
  }

  @Override
  public boolean onChunkData(ExtractorInput input) throws IOException {
    if (super.onChunkData(input)) {
      if (currentChunkSize == 0) {
        //Empty frame, just advance the clock
        advanceTime();
      }
      return true;
    }
    if (bytesRemainingInCurrentChunk == currentChunkSize) {
      if (timeUs == C.TIME_UNSET && aIndex.pendingIXChunkIndex == C.INDEX_UNSET) {
        timeUs = aIndex.computeChunkTimestampUs(currentChunkOffset);
        if (timeUs != C.TIME_UNSET) {
          logD("[aviIndex]onChunkData(chunk offset: %d) sync time to %d",
              currentChunkOffset, timeUs);
        }
      }
    }
    if (frameRemaining == 0) {
      //Find the next frame
      if (!findFrame(input)) {
        if (scratch.limit() >= currentChunkSize) {
          // Couldn't find an MPEG audio frame header in chunk.
          // Might be ID3 or leading 0s
          // Dump the chunk as it can mess up the (Pixel) decoder
          scratch.reset(0);
          // Not sure if this is the right thing to do.  Maybe nothing
          advanceTime();
        }
        return readComplete();
      }
    }
    int scratchBytes = scratch.bytesLeft();
    if (scratchBytes > 0) {
      trackOutput.sampleData(scratch, scratchBytes);
      frameRemaining -= scratchBytes;
      scratch.reset(0);
    }
    final int bytes = trackOutput.sampleData(input, Math.min(frameRemaining, bytesRemainingInCurrentChunk), false);
    frameRemaining -= bytes;
    if (frameRemaining == 0) {
      sendMetadata(header.frameSize);
      advanceTime();
    }
    bytesRemainingInCurrentChunk -= bytes;
    return readComplete();
  }

  private void advanceTime() {
    if (timeUs != C.TIME_UNSET) {
      timeUs += header.samplesPerFrame * C.MICROS_PER_SECOND / samplesPerSecond;
    }
  }

  private void sendMetadata(int size) {
    if (size > 0) {
      // logD("onChunkData() input %d pts %d" , currentChunkOffset, timeUs);
      trackOutput.sampleMetadata(
          timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null);
    }
  }

  private boolean readComplete() {
    return bytesRemainingInCurrentChunk == 0;
  }

  /**
   * Soft read from input to scratch
   * @param bytes to attempt to read
   * @return {@link C#RESULT_END_OF_INPUT} or number of bytes read.
   */
  private int readScratch(ExtractorInput input, int bytes) throws IOException {
    final int toRead = Math.min(bytes, bytesRemainingInCurrentChunk);
    scratch.ensureCapacity(scratch.limit() + toRead);
    final int read = input.read(scratch.getData(), scratch.limit(), toRead);
    if (read == C.RESULT_END_OF_INPUT) {
      return read;
    }
    bytesRemainingInCurrentChunk -= read;
    scratch.setLimit(scratch.limit() + read);
    return read;
  }

  private boolean findFrame(ExtractorInput input) throws IOException {
    int toRead = 4;
    while (bytesRemainingInCurrentChunk > 0 && readScratch(input, toRead) != C.RESULT_END_OF_INPUT) {
      while (scratch.bytesLeft() >= 4) {
        if (header.setForHeaderData(scratch.readInt())) {
          scratch.skipBytes(-4);
          frameRemaining = header.frameSize;
          return true;
        }
        scratch.skipBytes(-3);
      }
      // 16 is small, but if we end up reading multiple frames into scratch, things get complicated.
      // We should only loop on seek, so this is the lesser of the evils.
      toRead = Math.min(bytesRemainingInCurrentChunk, 16);
    }
    return false;
  }
}
