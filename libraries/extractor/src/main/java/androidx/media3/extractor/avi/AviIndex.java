package androidx.media3.extractor.avi;

import static androidx.media3.extractor.avi.StreamIndexChunk.TYPE_STANDARD_INDEX_CHUNK;
import static androidx.media3.extractor.avi.StreamIndexChunk.TYPE_SUPER_INDEX_CHUNK;

import android.util.Pair;

import androidx.media3.common.C;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;

import java.util.Arrays;

public class AviIndex {

  protected static final String TAG = "AviIndex";
  private static final int INITIAL_INDEX_SIZE = 512;

  private final long durationUs;
  private final int streamHeaderChunkCount;

  /* The initial indices are from idx1 chunk, they are also useful in OpenDML AVI. */
  public int initialIndicesCount;
  /* initialIndicesCount counts all entries, initialKeyframeIndicesCount counts keyframes only */
  private int initialKeyframeIndicesCount;
  /* initialKeyFrameOffsets and initialKeyFrameIndices are from idx1 chunk */
  protected long[] initialKeyFrameOffsets;
  /* Notice: keyFrameIndices base on an assumption that all frames info appears in idx1,
   * but this might only be true for video stream */
  protected int[] initialKeyFrameIndices;
  /* The data chunk size measuring helps to compute accurate timestamp for audio streams */
  protected long[] initialKeyframeChunkSizeCounts;
  /* A count variable helps building initialKeyFrameChunkSizeCounts */
  private int keyframeChunkSizeCount;

  /* Super index and ix## chunk are introduced by 'OpenDML AVI File Format Extensions'
   * Ver 1.02 by OpenDML AVI M-JPEG File Format Subcommittee: http://www.jmcgowan.com/odmlff2.pdf
   * Another helpful doc: https://www.alexander-noe.com/video/documentation/avi.pdf
   * It can either be a single index chunk, or a two tiered index, with a super index pointing to
   * interleaved index segments in the ‘movi’ data.
   * The video stream index chunk indexing all frames (indexChunkSizes equals to frame count),
   * while the audio stream index chunk usually indexing keyframes only.
   * ixIndicesCounts counts all indexed frames number in each segment, from super indices.
   */
  private int[] ixIndicesCounts;
  /* Summary of ixIndicesCounts */
  private int ixIndicesSum;
  /* ixKeyframeIndicesCounts counts indexed keyframes number in each segment, from super indices. */
  private int[] ixKeyframeIndicesCounts;
  /* The offsets of each ix chunk are from super indices. */
  private long[] ixChunkOffsets;
  /* The durations of each ix chunk, measured in stream ticks, are from super indices. */
  private long[] ixChunkDurations;
  /* ixChunkStreamTicks are durations added-up to each segment. */
  private long[] ixChunkStreamTicks;

  /* The followings index info is from each segmented AVI standard index chunk.
   * The index chunks are coming in 2 ways, one is seeking by requested, another is during
   * sequential 'movi' data chunks reading. For either way, if the data already read, just skip it.
   * So the segmented index info not combined into one, keep them segmented as 'ix##' chunks.
   */
  private int[][] keyframeIndicesList;
  private long[][] keyframeOffsetsList;
  private int[][] keyframeSizesList;

  /* Pending is in case seek point is requested, but information about that point is not ready.
   * In most cases, index chunks ('ix##') are spread all over 'movi' data. It's not guaranteed the
   * index chunk appears before the data chunk indexed.
   * Not going to collect all 'ix##' chunks before playback, it has to seek and read many times
   * (the function getNextIXChunkOffset() is helping to collect all 'ix##' chunks one by one).
   * So here is a lazy index chunks reading strategy: when user seeking to certain timestamp,
   * if keyframe-offset information of that timestamp is out of range, the offset of that 'ix##'
   * chunk is returned instead of keyframe-offset which seeking requested.
   * After all stream readers are ready, the extractor will resume the pending seeking.
   */
  public int pendingIXChunkIndex = C.INDEX_UNSET;
  public long pendingSeekPosition = C.INDEX_UNSET;

  private long videoFrameDurationUs;

  public static Pair<Integer, Integer> findPositionInArray(int value, int[][] array) {
    for (int i = 0; i < array.length; i++) {
      if (array[i].length > 0) {
        if (value < array[i][0]) {
          return null;
        }
        if (value > array[i][array[i].length - 1]) {
          continue;
        }
        int index = Arrays.binarySearch(array[i], value);
        if (index >= 0) {
          return new Pair<>(i, index);
        }
      }
    }
    return null;
  }

  public static Pair<Integer, Integer> findPositionInArray(long value, long[][] array) {
    for (int i = 0; i < array.length; i++) {
      if (array[i].length > 0) {
        if (value < array[i][0]) {
          return null;
        }
        if (value > array[i][array[i].length - 1]) {
          continue;
        }
        int index = Arrays.binarySearch(array[i], value);
        if (index >= 0) {
          return new Pair<>(i, index);
        }
      }
    }
    return null;
  }

  public static int getCountBase(int index, int[] arrayOfSizes) {
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

  public static int sumOfInts(int[] array) {
    if (array == null) {
      throw new IllegalArgumentException("The int array is null.");
    }
    return sumOfInts(array, array.length);
  }

  public static int sumOfInts(int[] array, int length) {
    if (array == null || length > array.length) {
      throw new IllegalArgumentException("The int array is null.");
    }
    int sum = 0;
    for (int i = 0; i < length; i++) {
      sum += array[i];
    }
    return sum;
  }

  public AviIndex(int streamHeaderChunkCount, long durationUs) {
    this.durationUs = durationUs;
    this.streamHeaderChunkCount = streamHeaderChunkCount;
    initialKeyFrameOffsets = new long[INITIAL_INDEX_SIZE];
    initialKeyFrameIndices = new int[INITIAL_INDEX_SIZE];
    initialKeyframeChunkSizeCounts = new long[INITIAL_INDEX_SIZE];
  }

  public void appendKeyFrameToInitialIndex(long offset, int size) {
    if (initialKeyframeIndicesCount == initialKeyFrameIndices.length) {
      initialKeyFrameOffsets = Arrays.copyOf(initialKeyFrameOffsets,
          initialKeyFrameOffsets.length * 3 / 2);
      initialKeyFrameIndices = Arrays.copyOf(initialKeyFrameIndices,
          initialKeyFrameIndices.length * 3 / 2);
      initialKeyframeChunkSizeCounts = Arrays.copyOf(initialKeyframeChunkSizeCounts,
          initialKeyframeChunkSizeCounts.length * 3 / 2);
    }
    initialKeyFrameOffsets[initialKeyframeIndicesCount] = offset;
    initialKeyFrameIndices[initialKeyframeIndicesCount] = initialIndicesCount;
    keyframeChunkSizeCount += size;
    initialKeyframeChunkSizeCounts[initialKeyframeIndicesCount] = keyframeChunkSizeCount;
    initialKeyframeIndicesCount++;
  }

  public void compactIndex() {
    initialKeyFrameOffsets = Arrays.copyOf(initialKeyFrameOffsets, initialKeyframeIndicesCount);
    initialKeyFrameIndices = Arrays.copyOf(initialKeyFrameIndices, initialKeyframeIndicesCount);
    initialKeyframeChunkSizeCounts = Arrays.copyOf(initialKeyframeChunkSizeCounts, initialKeyframeIndicesCount);
  }

  public long computeChunkTimestampUs(long chunkOffset) {
    long timestampUs = C.TIME_UNSET;
    if (indicesBasedOnAllFrames() || isOpenDmlExt()) {
      int chunkIndex = findChunkIndexByPosition(chunkOffset);
      if (chunkIndex != C.INDEX_UNSET) {
        if (indicesBasedOnAllFrames()) {
          timestampUs = getChunkTimestampUs(chunkIndex);
        }
        if (timestampUs == C.TIME_UNSET && isOpenDmlExt()) {
          timestampUs = computeChunkTimestampUsIX(chunkIndex);
        }
      }
    } else {
      int initialKeyframeIndex = findInitialKeyframeIndexByPosition(chunkOffset);
      if (initialKeyframeIndex != C.INDEX_UNSET) {
        timestampUs = (initialKeyframeIndex == 0 ? 0
            : initialKeyframeChunkSizeCounts[initialKeyframeIndex - 1]) * durationUs
            / initialKeyframeChunkSizeCounts[initialKeyframeChunkSizeCounts.length - 1];
      }
    }
    return timestampUs;
  }

  protected long computeChunkTimestampUsIX(int chunkIndex) {
    Pair<Integer, Integer> position = getIXPositionOfChunkIndex(chunkIndex);
    if (position == null) {
      return C.INDEX_UNSET;
    }
    int i = position.first, j = position.second;
    long ticks = i == 0 ? 0 : ixChunkStreamTicks[i - 1];
    ticks += ixChunkDurations[i]
        * sumOfInts(keyframeSizesList[i], j) / sumOfInts(keyframeSizesList[i]);
    return ticks * durationUs / ixChunkStreamTicks[ixChunkStreamTicks.length - 1];
  }


  private int findInitialKeyframeIndexByPosition(long chunkOffset) {
    int index = Arrays.binarySearch(initialKeyFrameOffsets, chunkOffset);
    return index >= 0 ? index : C.INDEX_UNSET;
  }

  public int findChunkIndexByPosition(long chunkOffset) {
    if (initialKeyFrameOffsets != null && initialKeyFrameOffsets.length > 0) {
      int index = Arrays.binarySearch(initialKeyFrameOffsets, chunkOffset);
      if (index >= 0) {
        return initialKeyFrameIndices[index];
      }
    }
    if (isOpenDmlExt()) {
      Pair<Integer, Integer> position = getIXPositionOfOffset(chunkOffset);
      if (position != null) {
        return keyframeIndicesList[position.first][position.second];
      }
    }
    return C.INDEX_UNSET;
  }

  /* This function provides all offsets of the 'ix' chunks need to be seek one by one */
  public long getNextIXChunkOffset() {
    if (ixChunkOffsets != null) {
      for (int i = 0; i < ixKeyframeIndicesCounts.length; i++) {
        if (ixKeyframeIndicesCounts[i] == 0) {
          return ixChunkOffsets[i];
        }
      }
    }
    return -1;
  }

  private Pair<Integer, Integer> getIXPositionOfChunkIndex(int chunkIndex) {
    if (!isOpenDmlExt()) {
      return null;
    }
    return findPositionInArray(chunkIndex, keyframeIndicesList);
  }

  private Pair<Integer, Integer> getIXPositionOfOffset(long offset) {
    if (!isOpenDmlExt()) {
      return null;
    }
    return findPositionInArray(offset, keyframeOffsetsList);
  }

  private long getFrameDurationUs() {
    return getChunkTimestampUs(1);
  }

  protected long getChunkTimestampUs(int chunkIndex) {
    return durationUs * chunkIndex / streamHeaderChunkCount;
  }

  public long getPendingSeekPosition() {
    if (!isOpenDmlExt() || pendingIXChunkIndex == C.INDEX_UNSET) {
      return C.INDEX_UNSET;
    }
    return pendingSeekPosition;
  }

  public SeekMap.SeekPoints getSeekPoints(long timeUs) {
    SeekMap.SeekPoints seekPoints = isOpenDmlExt()
        ? getSeekPointsIX(timeUs)
        : getSeekPoints(timeUs, initialKeyFrameIndices, initialKeyFrameOffsets);
    return seekPoints;
  }

  private SeekMap.SeekPoints getSeekPoints(long timeUs, int[] indices, long[] offsets) {
    int targetFrameIndex = (int) (timeUs / getFrameDurationUs());
    int keyFrameIndex = indicesBasedOnAllFrames()
        ? Util.binarySearchFloor(
        indices, targetFrameIndex, /* inclusive= */ true, /* stayInBounds= */ true)
        : (int) (1.0f * timeUs / durationUs * initialIndicesCount);
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
    int ixNo = locateSuperIndexNoByTimeUs(timeUs);
    if (ixKeyframeIndicesCounts[ixNo] > 0) {
      if (indicesBasedOnAllFrames()) {
        return getSeekPoints(timeUs, keyframeIndicesList[ixNo], keyframeOffsetsList[ixNo]);
      } else {
        long ticks = timeUs * ixChunkStreamTicks[ixChunkStreamTicks.length - 1] / durationUs;
        ticks -= (ixNo == 0 ? 0 : ixChunkStreamTicks[ixNo - 1]);
        int index = (int) (ticks * keyframeOffsetsList[ixNo].length / ixChunkDurations[ixNo]);
        return new SeekMap.SeekPoints(new SeekPoint(timeUs, keyframeOffsetsList[ixNo][index]));
      }
    } else {
      // Indices not ready, return the proper index chunk offset and waiting for being fed.
      int index = locateSuperIndexNoByTimeUs(timeUs);
      pendingSeekPosition = ixChunkOffsets[index];
      pendingIXChunkIndex = index;
      return null;
    }
  }

  private SeekPoint getSeekPoint(int keyFrameIndex, int[] keyFrameIndices, long[] keyFrameOffsets) {
    long timeUs = indicesBasedOnAllFrames()
        ? keyFrameIndices[keyFrameIndex] * getFrameDurationUs()
        : keyFrameIndex * durationUs / initialIndicesCount;
    return new SeekPoint(timeUs, keyFrameOffsets[keyFrameIndex]);
  }

  /* For the video stream, each chunk contains a frame. All chunks are indexed in idx1.
   * This is not the case for the audio streams.
   * If the idx1 index table not contains all frames, initialKeyFrameIndices is hard to use.
   */
  protected boolean indicesBasedOnAllFrames() {
    return initialIndicesCount == streamHeaderChunkCount
        || ixIndicesSum == streamHeaderChunkCount;
  }

  private boolean isOpenDmlExt() {
    return ixKeyframeIndicesCounts != null && ixKeyframeIndicesCounts.length > 0;
  }

  public boolean isReady() {
    return initialKeyframeIndicesCount != 0;
  }

  /* indexChunkPosition==C.INDEX_UNSET from one of the strl(stream lists) in hdrl(header list) body.
   * The chunk from 'hdrl' body can either be super index chunk or standard index chunk.
   */
  public void setIndices(StreamIndexChunk chunk, long indexChunkPosition, String indexChunkId) {
    if (chunk == null) {
      return;
    }
    if (chunk.indexType == TYPE_SUPER_INDEX_CHUNK) {
      // If superIndices already set, skip.
      if (ixChunkOffsets != null && ixChunkOffsets.length == chunk.entrySize) {
        return;
      }
      setSuperIndices(chunk, indexChunkId);
    } else if (chunk.indexType == TYPE_STANDARD_INDEX_CHUNK) {
      setStandardIndices(chunk, indexChunkPosition);
    }
  }

  private void setSuperIndices(StreamIndexChunk chunk, String indexChunkId) {
    int length = chunk.entrySize;
    ixChunkOffsets = new long[length];
    ixChunkDurations = new long[length];
    ixChunkStreamTicks = new long[length];
    if (keyframeIndicesList == null || keyframeOffsetsList == null
        || keyframeSizesList == null || ixKeyframeIndicesCounts == null) {
      ixKeyframeIndicesCounts = new int[length];
      Arrays.fill(ixKeyframeIndicesCounts, 0);
      ixIndicesCounts = new int[length];
      keyframeIndicesList = new int[length][0];
      keyframeOffsetsList = new long[length][0];
      keyframeSizesList = new int[length][0];
    }
    int index = 0;
    int durationCount = 0;
    StreamIndexChunk.SuperIndexEntry entry = new StreamIndexChunk.SuperIndexEntry();
    while (chunk.readSuperIndexEntry(entry)) {
      int indexChunkSize = (entry.size - 32) / 8;
      ixIndicesCounts[index] = indexChunkSize;
      ixChunkOffsets[index] = entry.offset;
      durationCount += entry.duration;
      ixChunkDurations[index] = entry.duration;
      ixChunkStreamTicks[index] = durationCount;
      index++;
    }
    ixIndicesSum = sumOfInts(ixIndicesCounts);
    Log.d(TAG, String.format("[aviIndex]SuperIndices have been set. " +
            "%s locations are %s, total size %d",
        indexChunkId, Arrays.toString(ixChunkOffsets), ixIndicesSum));
  }

  private void setStandardIndices(StreamIndexChunk chunk, long indexChunkPosition) {
    // No super index exists and from 'hdrl', so it is a single index chunk
    if (ixKeyframeIndicesCounts == null && indexChunkPosition == C.INDEX_UNSET) {
      ixKeyframeIndicesCounts = new int[1];
      ixIndicesCounts = new int[1];
      keyframeIndicesList = new int[1][0];
      keyframeOffsetsList = new long[1][0];
      keyframeSizesList = new int[1][0];
    }
    int ixNo = locateSuperIndexNoByIndexChunkReadingPosition(indexChunkPosition);
    if (ixNo == pendingIXChunkIndex) {
      pendingIXChunkIndex = C.INDEX_UNSET;
      pendingSeekPosition = C.INDEX_UNSET;
    }
    if (keyframeIndicesList[ixNo] == null || keyframeOffsetsList[ixNo] == null ||
        keyframeIndicesList[ixNo].length == 0 || keyframeOffsetsList[ixNo].length == 0) {
      keyframeIndicesList[ixNo] = new int[ixIndicesCounts[ixNo]];
      keyframeOffsetsList[ixNo] = new long[ixIndicesCounts[ixNo]];
      keyframeSizesList[ixNo] = new int[ixIndicesCounts[ixNo]];
      int indexChunkCount = getCountBase(ixNo, ixIndicesCounts);
      StreamIndexChunk.StandardIndexEntry entry = new StreamIndexChunk.StandardIndexEntry();
      while (chunk.readStandardIndexEntry(entry)) {
        // The bit 31 of size integer is set if this is NOT a keyframe
        if ((entry.size & 0x80000000) == 0) {
          if (ixKeyframeIndicesCounts[ixNo] < keyframeIndicesList[ixNo].length) {
            keyframeIndicesList[ixNo][ixKeyframeIndicesCounts[ixNo]] = indexChunkCount;
            // The offset is pointing to the data in the chunk, -8 to point to chunk header (id).
            keyframeOffsetsList[ixNo][ixKeyframeIndicesCounts[ixNo]] =
                chunk.baseOffset + entry.offset - 8;
            keyframeSizesList[ixNo][ixKeyframeIndicesCounts[ixNo]] = entry.size;
            ixKeyframeIndicesCounts[ixNo]++;
          } else {
            Log.w(TAG, "ArrayIndexOutOfBoundsException");
          }
        }
        indexChunkCount++;
      }
      keyframeOffsetsList[ixNo] = Arrays.copyOf(
          keyframeOffsetsList[ixNo], ixKeyframeIndicesCounts[ixNo]);
      keyframeIndicesList[ixNo] = Arrays.copyOf(
          keyframeIndicesList[ixNo], ixKeyframeIndicesCounts[ixNo]);
      Log.d(TAG, String.format("[aviIndex]Standard index chunk %d at %d has been set. " +
              "Offset ranges are [%d, %d].",
          ixNo, indexChunkPosition, keyframeOffsetsList[ixNo][0],
          keyframeOffsetsList[ixNo][keyframeOffsetsList[ixNo].length - 1]));
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

  public boolean willSeekToPosition(long position, long timeUs) {
    boolean newPending = false;
    if (isOpenDmlExt() && timeUs != C.TIME_UNSET && pendingIXChunkIndex == C.INDEX_UNSET) {
      int ixIndex = locateSuperIndexNoByTimeUs(timeUs);
      if (ixKeyframeIndicesCounts[ixIndex] == 0) {
        pendingIXChunkIndex = ixIndex;
        pendingSeekPosition = ixChunkOffsets[ixIndex];
        newPending = true;
      }
    }
    return newPending;
  }

  public String report() {
    return String.format(
        "chunk count %d, idx1 index count %d, "
            + "idx1 keyframe index count %d, durationUs %d, %s, %s",
        streamHeaderChunkCount, initialIndicesCount,
        initialKeyframeIndicesCount, durationUs,
        isOpenDmlExt() ? ("is OpenDML with " + ixIndicesSum + " indices") : "not OpenDML",
        indicesBasedOnAllFrames() ? "all frames are indexed" : "NOT all frames are indexed");
  }
}
