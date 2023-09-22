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

import static androidx.media3.extractor.avi.StreamIndexChunk.TYPE_STANDARD_INDEX_CHUNK;
import static androidx.media3.extractor.avi.StreamIndexChunk.TYPE_SUPER_INDEX_CHUNK;
import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.media3.common.C;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;

import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

/** Reads chunks holding sample data. */
/* package */ final class ChunkReader {

  /** Parser states. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    CHUNK_TYPE_VIDEO_COMPRESSED,
    CHUNK_TYPE_VIDEO_UNCOMPRESSED,
    CHUNK_TYPE_AUDIO,
  })
  private @interface ChunkType {}

  private static final String TAG = "ChunkReader";
  private static final int INITIAL_INDEX_SIZE = 512;
  private static final int CHUNK_TYPE_VIDEO_COMPRESSED = ('d' << 16) | ('c' << 24);
  private static final int CHUNK_TYPE_VIDEO_UNCOMPRESSED = ('d' << 16) | ('b' << 24);
  private static final int CHUNK_TYPE_AUDIO = ('w' << 16) | ('b' << 24);
  private static final int CHUNK_TYPE_INDEX = 'i' | ('x' << 8);

  protected final TrackOutput trackOutput;

  /** The chunk id fourCC (example: `01wb`), as defined in the index and the movi. */
  private final int chunkId;

  /** Secondary chunk id. Bad muxers sometimes use an uncompressed video id (db) for key frames */
  private final int alternativeChunkId;

  /** The index chunk id fourCC (example: `ix00` `ix01`). */
  private final int indexChunkId;

  private final long durationUs;
  private final int streamHeaderChunkCount;

  private int currentChunkSize;
  private int bytesRemainingInCurrentChunk;

  /** Number of chunks as calculated by the index */
  private int currentChunkIndex;

  /** Used to determine if current chunk is keyframe chunk, in case keyFrameIndices is useless
   *  because some AVI audio stream index chunk only contains keyframe chunk offset */
  private long currentChunkOffset;

  private int indexChunkCount;
  private int indexSize;

  /* keyFrameOffsets and keyFrameIndices are from idx1 chunk */
  private long[] keyFrameOffsets;
  /* Notice: keyFrameIndices base on an assumption that for all streams, all frames info appears
   * in idx1, but this is not always be true for audio stream */
  private int[] keyFrameIndices;

  /* Super index and ix## chunk are introduced by 'OpenDML AVI File Format Extensions'
   * Ver 1.02 by OpenDML AVI M-JPEG File Format Subcommittee: http://www.jmcgowan.com/odmlff2.pdf
   * It can either be a single index chunk, or a two tiered index, with a super index pointing to
   * interleaved index segments in the ‘movi’ data.
   * The video stream index chunk indexing all frames (indexChunkSizes equals to frame count),
   * while the audio stream index chunk usually indexing keyframes only.
   * keyframeIndexSizes counting for indexed keyframes number for each stream.
   */
  private int[] keyframeIndexSizes;
  private int[] indexChunkSizes;

  /* The values of ixChunkOffsets and ixChunkStreamTicks are from the super indices */
  private long[] ixChunkOffsets;
  private long[] ixChunkStreamTicks;

  /* keyframeOffsetsList and keyframeIndicesList are from AVI standard index chunk(s) */
  private long[][] keyframeOffsetsList;
  private int[][] keyframeIndicesList;

  /* Pending is in case seek point is requested, but information about that point is not ready.
   * In most cases, index chunks ('ix##') are spread all over movi data. It's not guaranteed the
   * index chunk appears before the data chunk indexed.
   * Not going to collect all 'ix##' chunks before playback, it has to seek and read many times.
   * So here is a lazy index chunks reading strategy: when user seeking to certain timestamp,
   * if keyframe-offset information of that timestamp is out of range, the offset of that 'ix##'
   * chunk is returned instead of keyframe-offset. After index chunk data read, return the target
   * seeking offset by getPendingSeekPosition() requested the extractor.
   * The reading of index chunks is in 2 ways, one is seeking described above, another is during
   * sequential 'movi' data chunks reading. If the index chunk already read, just skip it.
   * So the segmented offsets and indices are not combined into one, keep them as 'ix##' chunks.
   * There is also a function getNextIXChunkOffset() available for the extractor if going to collect
   * all the index chunks at once, by providing their offsets one by one. */
  private long pendingSeekTimeUs = C.TIME_UNSET;
  private int pendingIXChunkIndex = C.INDEX_UNSET;

  /* To inform that the coming chunk from onChunkData() is a index chunk */
  private boolean indexChunkStart = false;

  /* When audio stream index chunks not cover all frames, it's unable to get precise chunk index.
   * This impacts accurate timestamp. Calculating by stretching is improved to be a disaster way.
   * latestKeyframeIndex, indexCountSinceLatestKeyframe and videoFrameDurationUs are introduced
   * for AV sync, and calculate the timestamps of the audio indexed keyframes, which align with the
   * video frames, and these indexed keyframes have no audio stream all frame basis indices, so they
   * can not be calculated by audio frame duration.
   * Also a/v might have different data chunk sizes.
   * In some particular cases, it has been found that in the tail of the idx1 index table, couple
   * continuous video indices appears in the end, though it is interleaved evenly 1 by 1 all the way
   * from the beginning. It's weird that the a/v duration values are equal, a/v rate/scale are not.
   */
  private int latestKeyframeIndex = C.INDEX_UNSET;
  private int chunkCountSinceLatestKeyframe = 0;
  private long videoFrameDurationUs = C.LENGTH_UNSET;

  public ChunkReader(
      int id,
      @C.TrackType int trackType,
      long durationUs,
      int streamHeaderChunkCount,
      TrackOutput trackOutput) {
    Assertions.checkArgument(trackType == C.TRACK_TYPE_AUDIO || trackType == C.TRACK_TYPE_VIDEO);
    this.durationUs = durationUs;
    this.streamHeaderChunkCount = streamHeaderChunkCount;
    this.trackOutput = trackOutput;
    @ChunkType
    int chunkType =
        trackType == C.TRACK_TYPE_VIDEO ? CHUNK_TYPE_VIDEO_COMPRESSED : CHUNK_TYPE_AUDIO;
    chunkId = getChunkIdFourCc(id, chunkType);
    alternativeChunkId =
        trackType == C.TRACK_TYPE_VIDEO ? getChunkIdFourCc(id, CHUNK_TYPE_VIDEO_UNCOMPRESSED) : -1;
    indexChunkId = (('0' + id % 10) << 24) | (('0' + id / 10) << 16) | CHUNK_TYPE_INDEX;
    keyFrameOffsets = new long[INITIAL_INDEX_SIZE];
    keyFrameIndices = new int[INITIAL_INDEX_SIZE];
  }

  public void appendKeyFrameToIndex(long offset) {
    if (indexSize == keyFrameIndices.length) {
      keyFrameOffsets = Arrays.copyOf(keyFrameOffsets, keyFrameOffsets.length * 3 / 2);
      keyFrameIndices = Arrays.copyOf(keyFrameIndices, keyFrameIndices.length * 3 / 2);
    }
    keyFrameOffsets[indexSize] = offset;
    keyFrameIndices[indexSize] = indexChunkCount;
    indexSize++;
  }

  public void advanceCurrentChunk() {
    if (currentChunkIndex != C.INDEX_UNSET) {
      currentChunkIndex++;
      chunkCountSinceLatestKeyframe++;
    }
  }

  public long getCurrentChunkTimestampUs() {
    return indicesBasedOnAllFrames()
        ? getChunkTimestampUs(currentChunkIndex)
        : latestKeyframeIndex * videoFrameDurationUs
          + chunkCountSinceLatestKeyframe * getFrameDurationUs();
  }

  public long getFrameDurationUs() {
    return getChunkTimestampUs(/* chunkIndex= */ 1);
  }

  public void incrementIndexChunkCount() {
    indexChunkCount++;
  }

  public void compactIndex() {
    keyFrameOffsets = Arrays.copyOf(keyFrameOffsets, indexSize);
    keyFrameIndices = Arrays.copyOf(keyFrameIndices, indexSize);
  }

  public boolean handlesChunkId(int chunkId) {
    return this.chunkId == chunkId || alternativeChunkId == chunkId || indexChunkId == chunkId;
  }

  public boolean handlesIndexChunkId(int chunkId) {
    return indexChunkId == chunkId;
  }

  public boolean isCurrentFrameAKeyFrame() {
    boolean found = false;
    if (indicesBasedOnAllFrames()) {
      found = Arrays.binarySearch(keyFrameIndices, currentChunkIndex) >= 0;
      if (!found) {
        if (isOpenDmlExt() && (pendingSeekTimeUs == C.TIME_UNSET)) {
          int ixNo = locateSuperIndexNoForFrameIndex(currentChunkIndex);
          found = Arrays.binarySearch(keyframeIndicesList[ixNo], currentChunkIndex) >= 0;
        }
      }
    } else {
      int index = Arrays.binarySearch(keyFrameOffsets, currentChunkOffset);
      if (index >= 0) {
        latestKeyframeIndex = index;
        chunkCountSinceLatestKeyframe = 0;
        found = true;
      }
    }
    return found;
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
   */
  public boolean onChunkData(ExtractorInput input) throws IOException {
    if (currentChunkSize == bytesRemainingInCurrentChunk) {
      currentChunkOffset = input.getPosition() - 8;
    }
    if (indexChunkStart) {
      indexChunkStart = false;
      return processIndexChunk(input);
    }
    if (currentChunkIndex == C.INDEX_UNSET
        && currentChunkSize == bytesRemainingInCurrentChunk) {
      long position = input.getPosition() - 8;
      currentChunkIndex = getChunkIndexByPosition(position);
      if (currentChunkIndex != C.INDEX_UNSET) {
        Log.d(getTag(), String.format(
            "onChunkData(input:position: %d) seek and fix unset currentChunkIndex to %d",
            position, currentChunkIndex));
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
      }
      advanceCurrentChunk();
    }
    return done;
  }

  public void seekToPosition(long position, long timeUs) {
    if (indexSize == 0 && !isOpenDmlExt()) {
      currentChunkIndex = 0;
      return;
    }
    if (isOpenDmlExt()) {
      int ixIndex = locateSuperIndexNoByTimeUs(timeUs);
      if (keyframeIndexSizes[ixIndex] == 0) {
        pendingSeekTimeUs = timeUs;
        pendingIXChunkIndex = ixIndex;
        currentChunkIndex = C.INDEX_UNSET;
        Log.d(getTag(), String.format("seekToPosition(position: %d, timeUs: %d) results in " +
                "pendingSeekTimeUs %d pendingIXChunkIndex %d, and leave currentChunkIndex %d",
            position, timeUs, pendingSeekTimeUs, pendingIXChunkIndex, currentChunkIndex));
        return;
      }
    }
    currentChunkIndex = getChunkIndexByPosition(position);
    Log.d(getTag(), String.format("seekToPosition(position: %d, timeUs: %d) results in " +
        "currentChunkIndex %d", position, timeUs, currentChunkIndex));
  }

  public SeekMap.SeekPoints getSeekPoints(long timeUs) {
    SeekMap.SeekPoints seekPoints = isOpenDmlExt()
        ? getSeekPointsIX(timeUs)
        : getSeekPoints(timeUs, keyFrameIndices, keyFrameOffsets);
    Log.d(getTag(), String.format("getSeekPoints(timeUs: %d) as %s", timeUs, seekPoints));
    return seekPoints;
  }

  public void setVideoFrameDurationUs(long frameDurationUs) {
    this.videoFrameDurationUs = frameDurationUs;
  }

  private SeekMap.SeekPoints getSeekPoints(long timeUs, int[] indices, long[] offsets) {
    int targetFrameIndex = (int) (timeUs / getFrameDurationUs());
    int keyFrameIndex = indicesBasedOnAllFrames()
        ? Util.binarySearchFloor(
            indices, targetFrameIndex, /* inclusive= */ true, /* stayInBounds= */ true)
        : (int) (1.0f * timeUs / durationUs * indexChunkCount);
    SeekPoint precedingKeyFrameSeekPoint = getSeekPoint(keyFrameIndex, indices, offsets);
    if (indices[keyFrameIndex] == targetFrameIndex) {
      return new SeekMap.SeekPoints(precedingKeyFrameSeekPoint);
    }
    // The target frame is not a key frame, we look for the two closest ones
    if (keyFrameIndex + 1 < offsets.length) {
      return new SeekMap.SeekPoints(
          precedingKeyFrameSeekPoint, getSeekPoint(keyFrameIndex + 1, indices, offsets));
    } else {
      return new SeekMap.SeekPoints(precedingKeyFrameSeekPoint);
    }
  }

  private SeekMap.SeekPoints getSeekPointsIX(long timeUs) {
    int targetFrameIndex = (int) (timeUs / getFrameDurationUs());
    int ixNo = locateSuperIndexNoForFrameIndex(targetFrameIndex);
    if (keyframeIndexSizes[ixNo] > 0) {
      return getSeekPoints(timeUs, keyframeIndicesList[ixNo], keyframeOffsetsList[ixNo]);
    } else {
      // Indices not ready, return the proper index chunk offset and waiting for being fed.
      long totalStreamTicks = ixChunkStreamTicks[ixChunkStreamTicks.length - 1];
      long targetStreamTicks = (long) Math.floor(1.0f * timeUs / durationUs * totalStreamTicks);
      int index = Util.binarySearchFloor(
          ixChunkStreamTicks, targetStreamTicks, true, true);
      SeekPoint seekPointIX = new SeekPoint(timeUs, ixChunkOffsets[index]);
      return new SeekMap.SeekPoints(seekPointIX);
    }
  }

  /* This function provides all offsets of the 'ix' chunks need to be seek one by one */
  public long getNextIXChunkOffset() {
    if (ixChunkOffsets != null) {
      for (int i = 0; i < keyframeIndexSizes.length; i++) {
        if (keyframeIndexSizes[i] == 0) {
          return ixChunkOffsets[i];
        }
      }
    }
    return -1;
  }

  public long getPendingSeekPosition() {
    if (pendingSeekTimeUs == C.TIME_UNSET) {
      return -1;
    }
    // The indices are not ready yet.
    if (!isOpenDmlExt() || keyframeIndexSizes[pendingIXChunkIndex] == 0) {
      return -1;
    }

    int index = pendingIXChunkIndex;
    long timeUs = pendingSeekTimeUs;
    pendingSeekTimeUs = C.TIME_UNSET;
    pendingIXChunkIndex = C.INDEX_UNSET;

    int targetFrameIndex = (int) (timeUs / getFrameDurationUs());
    int keyFrameIndex = Util.binarySearchFloor(keyframeIndicesList[index],
        targetFrameIndex, /* inclusive= */ true, /* stayInBounds= */ true);
    currentChunkIndex = keyframeIndicesList[index][keyFrameIndex];
    Log.d(getTag(), "getPendingSeekPosition() set currentChunkIndex " + currentChunkIndex);
    return keyframeOffsetsList[index][keyFrameIndex];
  }

  /* indexChunkPosition -1 means from one of the 'strl'(stream lists) in 'hdrl'(header list) body.
   * The chunk from 'hdrl' body can either be super index chunk or standard index chunk.
   */
  public void setIndices(StreamIndexChunk chunk, long indexChunkPosition) {
    if (chunk == null) {
      return;
    }
    if (chunk.indexType == TYPE_SUPER_INDEX_CHUNK) {
      // If superIndices already set, skip.
      if (ixChunkOffsets != null && ixChunkOffsets.length == chunk.entrySize) {
        return;
      }
      setSuperIndices(chunk);
    } else if (chunk.indexType == TYPE_STANDARD_INDEX_CHUNK) {
      setStandardIndices(chunk, indexChunkPosition);
    }
  }

  private void setSuperIndices(StreamIndexChunk chunk) {
    int length = chunk.entrySize;
    ixChunkOffsets = new long[length];
    ixChunkStreamTicks = new long[length];
    if (keyframeIndicesList == null || keyframeOffsetsList == null || keyframeIndexSizes == null) {
      keyframeIndexSizes = new int[length];
      Arrays.fill(keyframeIndexSizes, 0);
      indexChunkSizes = new int[length];
      keyframeIndicesList = new int[length][0];
      keyframeOffsetsList = new long[length][0];
    }
    int index = 0;
    int durationCount = 0;
    StreamIndexChunk.SuperIndexEntry entry = new StreamIndexChunk.SuperIndexEntry();
    while (chunk.readSuperIndexEntry(entry)) {
      int indexChunkSize = (entry.size - 32) / 8;
      indexChunkSizes[index] = indexChunkSize;
      ixChunkOffsets[index] = entry.offset;
      durationCount += entry.duration;
      ixChunkStreamTicks[index] = durationCount;
      index++;
    }
    Log.d(getTag(), "SuperIndices have been set.");
  }

  private void setStandardIndices(StreamIndexChunk chunk, long indexChunkPosition) {
    // No super index exists and from 'hdrl', so it is a single index chunk
    if (keyframeIndexSizes == null && indexChunkPosition == -1) {
      keyframeIndexSizes = new int[1];
      indexChunkSizes = new int[1];
      keyframeIndicesList = new int[1][0];
      keyframeOffsetsList = new long[1][0];
    }
    int ixNo = locateSuperIndexNoByIndexChunkReadingPosition(indexChunkPosition);
    if (keyframeIndicesList[ixNo] == null || keyframeOffsetsList[ixNo] == null ||
        keyframeIndicesList[ixNo].length == 0 || keyframeOffsetsList[ixNo].length == 0) {
      keyframeIndicesList[ixNo] = new int[indexChunkSizes[ixNo]];
      keyframeOffsetsList[ixNo] = new long[indexChunkSizes[ixNo]];
      int indexChunkCount = getCountBase(ixNo, indexChunkSizes);
      StreamIndexChunk.StandardIndexEntry entry = new StreamIndexChunk.StandardIndexEntry();
      while (chunk.readStandardIndexEntry(entry)) {
        // The bit 31 of size integer is set if this is NOT a keyframe
        if ((entry.size & 0x80000000) == 0) {
          keyframeIndicesList[ixNo][keyframeIndexSizes[ixNo]] = indexChunkCount;
          // The offset is pointing to the data in the chunk, -8 to point to chunk header id.
          keyframeOffsetsList[ixNo][keyframeIndexSizes[ixNo]] = chunk.baseOffset + entry.offset;
          keyframeOffsetsList[ixNo][keyframeIndexSizes[ixNo]] += -8;
          keyframeIndexSizes[ixNo]++;
        }
        indexChunkCount++;
      }
      keyframeOffsetsList[ixNo] = Arrays.copyOf(
          keyframeOffsetsList[ixNo], keyframeIndexSizes[ixNo]);
      keyframeIndicesList[ixNo] = Arrays.copyOf(
          keyframeIndicesList[ixNo], keyframeIndexSizes[ixNo]);
      Log.d(getTag(), String.format("Standard index chunk at %d has been set %d of %d.",
          indexChunkPosition, ixNo + 1, keyframeIndexSizes.length));
    }
  }

  private int locateSuperIndexNoByIndexChunkReadingPosition(long position) {
    if (position == -1 || ixChunkOffsets == null || ixChunkOffsets.length == 0) {
      return 0;
    }
    for (int i = 1; i < ixChunkOffsets.length; i++) {
      if (position < ixChunkOffsets[i]) {
        return i - 1;
      }
    }
    return ixChunkOffsets.length - 1;
  }

  private int locateSuperIndexNoByTimeUs(long timeUs) {
    if (ixChunkOffsets == null || ixChunkOffsets.length == 0) {
      return 0;
    }
    long totalStreamTicks = ixChunkStreamTicks[ixChunkStreamTicks.length - 1];
    long targetStreamTicks = (long) Math.floor(1.0f * timeUs / durationUs * totalStreamTicks);
    return Util.binarySearchCeil(
        ixChunkStreamTicks, targetStreamTicks, true, true);
  }

  private int locateSuperIndexNoForFrameIndex(int frameIndex) {
    if (ixChunkOffsets == null || ixChunkOffsets.length == 0) {
      return 0;
    }
    float percentage = 1.0f * frameIndex * getFrameDurationUs() / durationUs;
    long streamTicks = (long) Math.floor(
        percentage * ixChunkStreamTicks[ixChunkStreamTicks.length - 1]);
    for (int i = 0; i < ixChunkStreamTicks.length; i++) {
      if (streamTicks < ixChunkStreamTicks[i]) {
        return i;
      }
    }
    return ixChunkStreamTicks.length - 1;
  }

  private long getChunkTimestampUs(int chunkIndex) {
    return durationUs * chunkIndex / streamHeaderChunkCount;
  }

  private SeekPoint getSeekPoint(int keyFrameIndex, int[] keyFrameIndices, long[] keyFrameOffsets) {
    long timeUs = indicesBasedOnAllFrames()
        ? keyFrameIndices[keyFrameIndex] * getFrameDurationUs()
        : keyFrameIndex * durationUs / indexChunkCount;
    return new SeekPoint(timeUs, keyFrameOffsets[keyFrameIndex]);
  }

  private boolean isOpenDmlExt() {
    return keyframeIndexSizes != null && keyframeIndexSizes.length > 0;
  }

  private static int getChunkIdFourCc(int streamId, @ChunkType int chunkType) {
    int tens = streamId / 10;
    int ones = streamId % 10;
    return (('0' + ones) << 8) | ('0' + tens) | chunkType;
  }

  private boolean processIndexChunk(ExtractorInput input) {
    try {
      ParsableByteArray chunkData = new ParsableByteArray(currentChunkSize);
      input.readFully(chunkData.getData(), 0, currentChunkSize);
      StreamIndexChunk streamIndexChunk = StreamIndexChunk.parseFrom(indexChunkId, chunkData);
      setIndices(streamIndexChunk, input.getPosition());
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private int getCountBase(int index, int[] arrayOfSizes) {
    if (index == 0 || arrayOfSizes == null) {
      return 0;
    }
    int count = 0;
    int limit = Math.min(index, arrayOfSizes.length - 1);
    for (int i = 1; i <= limit; i++) {
      count += arrayOfSizes[i - 1];
    }
    return count;
  }

  /* When not indicesBasedOnAllFrames(), ChunkIndex is not accurate enough to one frame */
  private int getChunkIndexByPosition(long position) {
    if (isOpenDmlExt()) {
      for (int i = 0; i < keyframeIndexSizes.length; i++) {
        if (keyframeIndexSizes[i] > 0) {
          if (position < keyframeOffsetsList[i][0]) {
            return C.INDEX_UNSET;
          }
          if (position > keyframeOffsetsList[i][keyframeIndexSizes[i] - 1]) {
            continue;
          }
          int index = Arrays.binarySearch(keyframeOffsetsList[i], position);
          if (index >= 0) {
            return keyframeIndicesList[i][index];
          }
        }
      }
    } else {
      int index = Arrays.binarySearch(keyFrameOffsets, position);
      if (index >= 0) {
        latestKeyframeIndex = index;
        chunkCountSinceLatestKeyframe = 0;
        return indicesBasedOnAllFrames()
            ? keyFrameIndices[index]
            : (int) (videoFrameDurationUs * index / getFrameDurationUs());
      }
    }
    return C.INDEX_UNSET;
  }

  /* If the idx1 index table not contains all frames, then the indexChunkCount is not counting for
   * all frames, it should not be used for keyFrameIndices setup, keyFrameIndices is incorrect
   */
  private boolean indicesBasedOnAllFrames() {
    return isOpenDmlExt() || indexChunkCount == streamHeaderChunkCount;
  }

  private String getTag() {
    return TAG + (isAudio() ? "(Audio)" : "(Video)");
  }

  public String report() {
    return String.format(
        "AVI stream report for seeking: %s track with id %c%c%c%c, chunk count %d, index count %d, "
            + "keyframe index count %d, %s",
        isAudio() ? "Audio" : (isVideo() ? "Video" : "Unknown"),
        (char) (chunkId & 0x000000FF),
        (char) ((chunkId & 0x0000FF00) >> 8),
        (char) ((chunkId & 0x00FF0000) >> 16),
        (char) ((chunkId & 0xFF000000) >> 24),
        streamHeaderChunkCount, indexChunkCount, indexSize,
        isOpenDmlExt() ? "is OpenDML and has IX chunks" : "not an OpenDML file");
  }
}
