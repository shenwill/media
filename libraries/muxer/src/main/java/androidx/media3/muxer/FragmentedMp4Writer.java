/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.muxer;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.muxer.Mp4Muxer.TrackToken;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * An {@link Mp4Writer} implementation which writes samples into multiple fragments as per the
 * fragmented MP4 (ISO/IEC 14496-12) standard.
 */
/* package */ final class FragmentedMp4Writer extends Mp4Writer {
  /** Provides a limited set of sample metadata. */
  public static class SampleMetadata {
    public final long durationVu;
    public final int size;
    public final int flags;

    public SampleMetadata(long durationsVu, int size, int flags) {
      this.durationVu = durationsVu;
      this.size = size;
      this.flags = flags;
    }
  }

  private final int fragmentDurationUs;

  private @MonotonicNonNull Track videoTrack;
  private int currentFragmentSequenceNumber;
  private boolean headerCreated;
  private long minInputPresentationTimeUs;
  private long maxTrackDurationUs;

  public FragmentedMp4Writer(
      FileOutputStream outputStream,
      Mp4MoovStructure moovGenerator,
      AnnexBToAvccConverter annexBToAvccConverter,
      int fragmentDurationUs) {
    super(outputStream, moovGenerator, annexBToAvccConverter);
    this.fragmentDurationUs = fragmentDurationUs;
    minInputPresentationTimeUs = Long.MAX_VALUE;
    currentFragmentSequenceNumber = 1;
  }

  @Override
  public TrackToken addTrack(int sortKey, Format format) {
    Track track = new Track(format);
    tracks.add(track);
    if (MimeTypes.isVideo(format.sampleMimeType)) {
      videoTrack = track;
    }
    return track;
  }

  @Override
  public void writeSampleData(
      TrackToken token, ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo)
      throws IOException {
    checkArgument(token instanceof Track);
    if (!headerCreated) {
      createHeader();
      headerCreated = true;
    }
    Track track = (Track) token;
    if (shouldFlushPendingSamples(track, bufferInfo)) {
      createFragment();
    }
    track.writeSampleData(byteBuffer, bufferInfo);
    BufferInfo firstPendingSample = checkNotNull(track.pendingSamplesBufferInfo.peekFirst());
    BufferInfo lastPendingSample = checkNotNull(track.pendingSamplesBufferInfo.peekLast());
    minInputPresentationTimeUs =
        min(minInputPresentationTimeUs, firstPendingSample.presentationTimeUs);
    maxTrackDurationUs =
        max(
            maxTrackDurationUs,
            lastPendingSample.presentationTimeUs - firstPendingSample.presentationTimeUs);
  }

  @Override
  public void close() throws IOException {
    try {
      createFragment();
    } finally {
      output.close();
      outputStream.close();
    }
  }

  private void createHeader() throws IOException {
    output.position(0L);
    output.write(Boxes.ftyp());
    // TODO: b/262704382 - Add some free space in the moov box to fit any newly added metadata and
    //  write moov box again in the close() method.
    // The minInputPtsUs is actually ignored as there are no pending samples to write.
    output.write(
        moovGenerator.moovMetadataHeader(
            tracks, /* minInputPtsUs= */ 0L, /* isFragmentedMp4= */ true));
  }

  private boolean shouldFlushPendingSamples(
      Track track, MediaCodec.BufferInfo nextSampleBufferInfo) {
    // If video track is present then fragment will be created based on group of pictures and
    // track's duration so far.
    if (videoTrack != null) {
      // Video samples can be written only when complete group of pictures are present.
      if (track.equals(videoTrack)
          && track.hadKeyframe
          && ((nextSampleBufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) > 0)) {
        BufferInfo firstPendingSample = checkNotNull(track.pendingSamplesBufferInfo.peekFirst());
        BufferInfo lastPendingSample = checkNotNull(track.pendingSamplesBufferInfo.peekLast());
        return lastPendingSample.presentationTimeUs - firstPendingSample.presentationTimeUs
            >= fragmentDurationUs;
      }
      return false;
    } else {
      return maxTrackDurationUs >= fragmentDurationUs;
    }
  }

  private void createFragment() throws IOException {
    // Write moof box.
    List<ByteBuffer> trafBoxes = createTrafBoxes();
    if (trafBoxes.isEmpty()) {
      return;
    }
    output.write(Boxes.moof(Boxes.mfhd(currentFragmentSequenceNumber), trafBoxes));

    writeMdatBox();

    currentFragmentSequenceNumber++;
  }

  private List<ByteBuffer> createTrafBoxes() {
    List<ByteBuffer> trafBoxes = new ArrayList<>();
    for (int i = 0; i < tracks.size(); i++) {
      Track currentTrack = tracks.get(i);
      if (!currentTrack.pendingSamplesBufferInfo.isEmpty()) {
        List<SampleMetadata> samplesMetadata =
            processPendingSamplesBufferInfo(currentTrack, currentFragmentSequenceNumber);
        ByteBuffer trun = Boxes.trun(samplesMetadata);
        trafBoxes.add(Boxes.traf(Boxes.tfhd(/* trackId= */ i + 1), trun));
      }
    }
    return trafBoxes;
  }

  private void writeMdatBox() throws IOException {
    long mdatStartPosition = output.position();
    // 4 bytes (indicating a 64-bit length field) + 4 bytes (box name) + 8 bytes (the actual length)
    ByteBuffer header = ByteBuffer.allocate(16);
    // This 32-bit integer in general contains the total length of the box. Here value 1 indicates
    // that the actual length is stored as 64-bit integer after the box name.
    header.putInt(1);
    header.put(Util.getUtf8Bytes("mdat"));
    header.putLong(16); // The total box length so far.
    header.flip();
    output.write(header);

    long bytesWritten = 0;
    for (int i = 0; i < tracks.size(); i++) {
      Track currentTrack = tracks.get(i);
      while (!currentTrack.pendingSamplesByteBuffer.isEmpty()) {
        ByteBuffer currentSampleByteBuffer = currentTrack.pendingSamplesByteBuffer.removeFirst();

        // Convert the H.264/H.265 samples from Annex-B format (output by MediaCodec) to
        // Avcc format (required by MP4 container).
        if (MimeTypes.isVideo(currentTrack.format.sampleMimeType)) {
          annexBToAvccConverter.process(currentSampleByteBuffer);
        }
        bytesWritten += output.write(currentSampleByteBuffer);
      }
    }

    long currentPosition = output.position();

    // Skip 4 bytes (64-bit length indication) + 4 bytes (box name).
    output.position(mdatStartPosition + 8);
    ByteBuffer mdatSize = ByteBuffer.allocate(8); // 64-bit length.
    // Additional 4 bytes (64-bit length indication) + 4 bytes (box name) + 8 bytes (actual length).
    mdatSize.putLong(bytesWritten + 16);
    mdatSize.flip();
    output.write(mdatSize);
    output.position(currentPosition);
  }

  private List<SampleMetadata> processPendingSamplesBufferInfo(
      Track track, int fragmentSequenceNumber) {
    List<BufferInfo> sampleBufferInfos = new ArrayList<>(track.pendingSamplesBufferInfo);

    List<Long> sampleDurations =
        Boxes.convertPresentationTimestampsToDurationsVu(
            sampleBufferInfos,
            /* firstSamplePresentationTimeUs= */ fragmentSequenceNumber == 1
                ? minInputPresentationTimeUs
                : sampleBufferInfos.get(0).presentationTimeUs,
            track.videoUnitTimebase(),
            Mp4Muxer.LAST_FRAME_DURATION_BEHAVIOR_DUPLICATE_PREV_DURATION);

    List<SampleMetadata> pendingSamplesMetadata = new ArrayList<>(sampleBufferInfos.size());
    for (int i = 0; i < sampleBufferInfos.size(); i++) {
      pendingSamplesMetadata.add(
          new SampleMetadata(
              sampleDurations.get(i),
              sampleBufferInfos.get(i).size,
              sampleBufferInfos.get(i).flags));
    }

    // Clear the queue.
    track.pendingSamplesBufferInfo.clear();
    return pendingSamplesMetadata;
  }
}
