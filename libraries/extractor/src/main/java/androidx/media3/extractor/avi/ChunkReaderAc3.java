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
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.Ac3Reader;

import java.io.IOException;

/** Reads AC3 audio chunks holding sample data. */
/* package */ final class ChunkReaderAc3 extends ChunkReader {

  private final Ac3Reader ac3Reader = new Ac3Reader();
  private final ParsableByteArray scratch = new ParsableByteArray(8);

  /**
   * Current time in the stream
   */
  private long timeUs;

  public ChunkReaderAc3(
      int id, long durationUs, int streamHeaderChunkCount, TrackOutput trackOutput) {
    super(
        id, getChunkIdFourCc(id, CHUNK_TYPE_AUDIO), /* alternativeChunkId */ -1,
        durationUs, streamHeaderChunkCount, trackOutput);
    ac3Reader.setTrackOutput(trackOutput);
  }

  @Override
  protected void invalidateCurrentChunkPosition() {
    timeUs = C.TIME_UNSET;
  }

  @Override
  public boolean onChunkData(ExtractorInput input) throws IOException {
    if (super.onChunkData(input)) {
      return true;
    }
    if (bytesRemainingInCurrentChunk == currentChunkSize) {
      if (timeUs == C.TIME_UNSET) {
        timeUs = aIndex.computeChunkTimestampUs(currentChunkOffset);
        if (timeUs != C.TIME_UNSET) {
          logD("[aviIndex]onChunkData(chunk offset: %d) sync time to %d",
              currentChunkOffset, timeUs);
          ac3Reader.packetStarted(timeUs, 0);
        }
      }
    }
    if (timeUs != C.TIME_UNSET) {
      final int toRead = bytesRemainingInCurrentChunk;
      scratch.ensureCapacity(toRead);
      int read = input.read(scratch.getData(), 0, toRead);
      scratch.setLimit(read);
      scratch.setPosition(0);
      bytesRemainingInCurrentChunk -= read;
      ac3Reader.consume(scratch);
    }

    return bytesRemainingInCurrentChunk == 0;
  }
}
