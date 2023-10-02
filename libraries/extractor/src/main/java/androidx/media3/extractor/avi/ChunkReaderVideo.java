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
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.TrackOutput;

import java.io.IOException;

/** Reads video chunks holding sample data. */
/* package */ class ChunkReaderVideo extends ChunkReader {

  protected static final String TAG = "ChunkReaderVideo";

  /** Number of chunks as calculated by the index */
  private int currentChunkIndex;

  public ChunkReaderVideo(
      int id,
      long durationUs,
      int streamHeaderChunkCount,
      TrackOutput trackOutput) {
    super(
        id,
        getChunkIdFourCc(id, CHUNK_TYPE_VIDEO_COMPRESSED),
        getChunkIdFourCc(id, CHUNK_TYPE_VIDEO_UNCOMPRESSED),
        durationUs, streamHeaderChunkCount, trackOutput);
  }

  /**
   * Provides data associated to the current chunk and returns whether the full chunk has been
   * parsed.
   */
  @Override
  public boolean onChunkData(ExtractorInput input) throws IOException {
    if (super.onChunkData(input)) {
      return true;
    }
    if (currentChunkIndex == C.INDEX_UNSET
        && currentChunkSize == bytesRemainingInCurrentChunk
        && aIndex.pendingIXChunkIndex == C.INDEX_UNSET) {
      currentChunkIndex = aIndex.findChunkIndexByPosition(currentChunkOffset);
      if (currentChunkIndex != C.INDEX_UNSET) {
        logD("[aviIndex]onChunkData(input:position: %d) fix unset currentChunkIndex to %d timeUs %d",
            currentChunkOffset, currentChunkIndex, getCurrentChunkTimestampUs());
      }
    }
    if (currentChunkIndex == C.INDEX_UNSET) {
      input.skipFully(currentChunkSize);
      bytesRemainingInCurrentChunk = 0;
      return true;
    }
    bytesRemainingInCurrentChunk -=
        trackOutput.sampleData(input, bytesRemainingInCurrentChunk, false);
    boolean done = bytesRemainingInCurrentChunk == 0;
    if (done) {
      if (currentChunkSize > 0) {
        trackOutput.sampleMetadata(
            getCurrentChunkTimestampUs(),
            (isCurrentFrameAKeyFrame() ? C.BUFFER_FLAG_KEY_FRAME : 0),
            currentChunkSize,
            0,
            null);
        // logD("onChunkData() input %d pts %d", currentChunkOffset, getCurrentChunkTimestampUs());
      }
      advanceCurrentChunk();
    }
    return done;
  }

  @Override
  public void willSeekToPosition(long position, long timeUs) {
    if (!aIndex.isReady()) {
      currentChunkIndex = 0;
      return;
    }
    super.willSeekToPosition(position, timeUs);
  }

  @Override
  protected void invalidateCurrentChunkPosition() {
    currentChunkIndex = C.INDEX_UNSET;
  }

  private void advanceCurrentChunk() {
    if (currentChunkIndex != C.INDEX_UNSET) {
      currentChunkIndex++;
    }
  }

  private long getCurrentChunkTimestampUs() {
    return getChunkTimestampUs(currentChunkIndex);
  }

  private long getChunkTimestampUs(int chunkIndex) {
    return durationUs * chunkIndex / streamHeaderChunkCount;
  }

  private boolean isCurrentFrameAKeyFrame() {
    return aIndex.findChunkIndexByPosition(currentChunkOffset) != C.INDEX_UNSET;
  }
}
