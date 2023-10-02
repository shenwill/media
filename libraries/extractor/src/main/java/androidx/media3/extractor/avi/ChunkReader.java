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

import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.media3.common.C;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;

import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Reads chunks holding sample data. */
/* package */ abstract class ChunkReader {

  /** Parser states. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    CHUNK_TYPE_VIDEO_COMPRESSED,
    CHUNK_TYPE_VIDEO_UNCOMPRESSED,
    CHUNK_TYPE_AUDIO,
  })
  protected @interface ChunkType {}

  protected static final String TAG = "ChunkReader";
  protected static final int CHUNK_TYPE_VIDEO_COMPRESSED = ('d' << 16) | ('c' << 24);
  protected static final int CHUNK_TYPE_VIDEO_UNCOMPRESSED = ('d' << 16) | ('b' << 24);
  protected static final int CHUNK_TYPE_AUDIO = ('w' << 16) | ('b' << 24);
  private static final int CHUNK_TYPE_INDEX = 'i' | ('x' << 8);

  protected final TrackOutput trackOutput;

  /** The chunk id fourCC (example: `01wb`), as defined in the index and the movi. */
  private final int chunkId;

  /** Secondary chunk id. Bad muxers sometimes use an uncompressed video id (db) for key frames */
  private final int alternativeChunkId;

  /** The index chunk id fourCC (example: `ix00` `ix01`). */
  private final int indexChunkId;

  protected final long durationUs;
  protected final int streamHeaderChunkCount;

  protected int currentChunkSize;
  protected int bytesRemainingInCurrentChunk;

  /** Used to determine if current chunk is keyframe chunk, in case keyFrameIndices is useless
   *  because some AVI audio stream index chunk only contains keyframe chunk offset */
  protected long currentChunkOffset;

  /* To inform that the coming chunk from onChunkData() is a index chunk */
  protected boolean indexChunkStart = false;
  protected final AviIndex aIndex;

  public static String chunkIdFourCc(int chunkId) {
    return String.format("%c%c%c%c",
        (char) (chunkId & 0x000000FF),
        (char) ((chunkId & 0x0000FF00) >> 8),
        (char) ((chunkId & 0x00FF0000) >> 16),
        (char) ((chunkId & 0xFF000000) >> 24));
  }

  protected static int getChunkIdFourCc(int streamId, @ChunkType int chunkType) {
    int tens = streamId / 10;
    int ones = streamId % 10;
    return (('0' + ones) << 8) | ('0' + tens) | chunkType;
  }

  public ChunkReader(
      int id,
      int chunkId,
      int alternativeChunkId,
      long durationUs,
      int streamHeaderChunkCount,
      TrackOutput trackOutput) {
    this.chunkId = chunkId;
    this.alternativeChunkId = alternativeChunkId;
    this.durationUs = durationUs;
    this.streamHeaderChunkCount = streamHeaderChunkCount;
    this.trackOutput = trackOutput;
    indexChunkId = (('0' + id % 10) << 24) | (('0' + id / 10) << 16) | CHUNK_TYPE_INDEX;
    aIndex = new AviIndex(streamHeaderChunkCount, durationUs);
  }

  public void appendKeyFrameToInitialIndex(long offset, int size) {
    aIndex.appendKeyFrameToInitialIndex(offset, size);
  }

  public void incrementIndexChunkCount() {
    aIndex.initialIndicesCount++;
  }

  public void compactIndex() {
    aIndex.compactIndex();
  }

  public boolean handlesChunkId(int chunkId) {
    return this.chunkId == chunkId || alternativeChunkId == chunkId || indexChunkId == chunkId;
  }

  public boolean handlesIndexChunkId(int chunkId) {
    return indexChunkId == chunkId;
  }

  public boolean isVideo() {
    return (chunkId & CHUNK_TYPE_VIDEO_COMPRESSED) == CHUNK_TYPE_VIDEO_COMPRESSED;
  }

  public boolean isAudio() {
    return (chunkId & CHUNK_TYPE_AUDIO) == CHUNK_TYPE_AUDIO;
  }

  /**
   * Prepares for parsing a chunk with the given {@code size}.
   */
  public void onChunkStart(int chunkId, int size) {
    if (chunkId == indexChunkId) {
      indexChunkStart = true;
    }
    currentChunkSize = size;
    bytesRemainingInCurrentChunk = size;
  }

  /**
   * Provides data associated to the current chunk and returns whether the full chunk has been
   * parsed.
   * This method must be called by sub-class to read and process index chunk.
   */
  public boolean onChunkData(ExtractorInput input) throws IOException {
    if (currentChunkSize == bytesRemainingInCurrentChunk) {
      currentChunkOffset = input.getPosition() - 8;
    }
    if (currentChunkSize == 0) {
      return true;
    }
    if (indexChunkStart) {
      indexChunkStart = false;
      return processIndexChunk(input);
    }
    return false;
  }

  /* This method must be called by sub-class to check if index information is required.*/
  public void willSeekToPosition(long position, long timeUs) {
    if (!aIndex.isReady()) {
      return;
    }
    invalidateCurrentChunkPosition();
    if (aIndex.willSeekToPosition(position, timeUs)) {
      logD("[aviIndex]willSeekToPosition(position: %d, timeUs: %d) results in " +
              "pendingSeekPosition %d pendingIXChunkIndex %d",
          position, timeUs, aIndex.pendingSeekPosition, aIndex.pendingIXChunkIndex);
    } else {
      logD("[aviIndex]willSeekToPosition(position: %d, timeUs: %d): invalidateCurrentChunkPosition()",
          position, timeUs);
    }
  }

  public SeekMap.SeekPoints getSeekPoints(long timeUs) {
    SeekMap.SeekPoints seekPoints = aIndex.getSeekPoints(timeUs);
    logD("[aviIndex]getSeekPoints(timeUs: %d) as %s", timeUs, seekPoints);
    return seekPoints;
  }

  public long getPendingSeekPosition() {
    long pendingSeekPosition = aIndex.getPendingSeekPosition();
    if (pendingSeekPosition != C.INDEX_UNSET) {
      logD("[aviIndex]getPendingSeekPosition() return %d", pendingSeekPosition);
    }
    return pendingSeekPosition;
  }

  private boolean processIndexChunk(ExtractorInput input) {
    try {
      ParsableByteArray chunkData = new ParsableByteArray(currentChunkSize);
      input.readFully(chunkData.getData(), 0, currentChunkSize);
      StreamIndexChunk streamIndexChunk = StreamIndexChunk.parseFrom(indexChunkId, chunkData);
      aIndex.setIndices(streamIndexChunk, currentChunkOffset, chunkIdFourCc(indexChunkId));
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  public void setIndices(StreamIndexChunk chunk, long indexChunkPosition) {
    aIndex.setIndices(chunk, indexChunkPosition, chunkIdFourCc(indexChunkId));
  }

  abstract void invalidateCurrentChunkPosition();

  protected void logD(String format, Object... args) {
    Log.d(TAG, getLogString(format, args));
  }

  private String getLogString(String format, Object... args) {
    return (isAudio() ? "(Audio)" : (isVideo() ? "(Video)" : "(Unknown)"))
        + String.format(format, args);
  }

  public String report() {
    return String.format(
        "[aviIndex]AVI stream report: %s track with id %s, %s",
        isAudio() ? "Audio" : (isVideo() ? "Video" : "Unknown"),
        chunkIdFourCc(chunkId),
        aIndex.report());
  }
}
