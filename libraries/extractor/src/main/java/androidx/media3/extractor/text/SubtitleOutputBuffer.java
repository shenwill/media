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
package androidx.media3.extractor.text;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderOutputBuffer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Base class for {@link SubtitleDecoder} output buffers. */
@UnstableApi
public abstract class SubtitleOutputBuffer extends DecoderOutputBuffer implements Subtitle {

  @Nullable private Subtitle subtitle;
  private long subsampleOffsetUs;

  private List<List<Cue>> pastCues = new ArrayList();

  /**
   * Sets the content of the output buffer, consisting of a {@link Subtitle} and associated
   * metadata.
   *
   * @param timeUs The time of the start of the subtitle in microseconds.
   * @param subtitle The subtitle.
   * @param subsampleOffsetUs An offset that must be added to the subtitle's event times, or {@link
   *     Format#OFFSET_SAMPLE_RELATIVE} if {@code timeUs} should be added.
   */
  public void setContent(long timeUs, Subtitle subtitle, long subsampleOffsetUs) {
    this.timeUs = timeUs;
    this.subtitle = subtitle;
    this.subsampleOffsetUs =
        subsampleOffsetUs == Format.OFFSET_SAMPLE_RELATIVE ? this.timeUs : subsampleOffsetUs;
  }

  @Override
  public int getEventTimeCount() {
    return Assertions.checkNotNull(subtitle).getEventTimeCount();
  }

  @Override
  public long getEventTime(int index) {
    return Assertions.checkNotNull(subtitle).getEventTime(index) + subsampleOffsetUs;
  }

  @Override
  public int getNextEventTimeIndex(long timeUs) {
    return Assertions.checkNotNull(subtitle).getNextEventTimeIndex(timeUs - subsampleOffsetUs);
  }

  @Override
  public List<Cue> getCues(long timeUs) {
    List<Cue> cues = Assertions.checkNotNull(subtitle).getCues(timeUs - subsampleOffsetUs);
    boolean drawn = false;
    for (Cue cue : cues) {
      if (cue.bitmapDrawContext != null && cue.bitmap == null) {
        cue.bitmap = cue.bitmapDrawContext.draw();
        drawn = true;
      }
    }
    if (drawn) {
      pastCues.add(cues);
    }
    if (pastCues.size() > 3) {
      List<Cue> cuesToShrink = pastCues.remove(0);
      for (Cue cue : cuesToShrink) {
        if (cue.bitmapDrawContext != null) {
          if (cue.bitmap != null) {
            cue.bitmap.recycle();
            cue.bitmap = null;
          }
        }
      }
    }
    return cues;
  }

  @Override
  public void clear() {
    super.clear();
    for (List<Cue> cues : pastCues) {
      for (Cue cue : cues) {
        if (cue.bitmap != null && !cue.bitmap.isRecycled()) {
          cue.bitmap.recycle();
          cue.bitmap = null;
        }
      }
    }
    pastCues.clear();
    subtitle = null;
  }
}
