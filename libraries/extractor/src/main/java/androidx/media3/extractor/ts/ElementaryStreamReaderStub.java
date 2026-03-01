package androidx.media3.extractor.ts;

import android.os.Bundle;
import android.util.Pair;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.ExtractorOutput;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ElementaryStreamReaderStub implements ElementaryStreamReader {

    public interface IFormatOutput {
        void outputFormat(Format format);
    }

    private static final String TAG = "StreamReaderStub";
    private static final int AUDIO_ATTRIB_SIZE = 8, SUB_ATTRIB_SIZE = 6;
    private static final int AUDIO_CONTROL_SIZE = 2, SUB_CONTROL_SIZE = 4;

    public ExtractorOutput extractorOutput;
    public PesReader.TrackIdGenerator idGenerator;

    private final int audioReaderNum, vobsubReaderNum;
    private final SparseArray<Pair<ElementaryStreamReader, Format>> audioReaders;
    private final SparseArray<ElementaryStreamReader> vobsubReaders;
    private final byte[] vtsAudioAttrBytes, vtsSubpAttrBytes, vtsVideoAttrBytes,
        audioControlBytes, subpControlBytes, paletteBytes;

    public ElementaryStreamReaderStub(byte[][] initDataBytes) {
        vtsAudioAttrBytes = initDataBytes != null ? initDataBytes[0] : null;
        audioReaderNum = vtsAudioAttrBytes != null
            ? vtsAudioAttrBytes.length / AUDIO_ATTRIB_SIZE : 0;
        audioReaders = new SparseArray(audioReaderNum > 0 ? audioReaderNum : 0);
        vtsSubpAttrBytes = initDataBytes != null ? initDataBytes[1] : null;
        vobsubReaderNum = vtsSubpAttrBytes != null
            ? vtsSubpAttrBytes.length / SUB_ATTRIB_SIZE : 10;
        vobsubReaders = new SparseArray(vobsubReaderNum);
        vtsVideoAttrBytes = initDataBytes != null ? initDataBytes[2] : null;
        audioControlBytes = initDataBytes != null ? initDataBytes[3] : null;
        subpControlBytes = initDataBytes != null ? initDataBytes[4] : null;
        paletteBytes = initDataBytes != null ? initDataBytes[5] : null;
    }

    public void endTracks(SparseArray<ElementaryStreamReader> readerArray) {
        for (int i = 0, l = audioReaders.size(); i < l; i++) {
            int startCode = audioReaders.keyAt(i);
            Pair<ElementaryStreamReader, Format> pair = audioReaders.get(startCode);
            if (pair != null) {
                ElementaryStreamReader reader = pair.first;
                readerArray.put(startCode, reader);
                if (reader instanceof IFormatOutput) {
                    ((IFormatOutput) reader).outputFormat(pair.second);
                }
                Log.i(TAG, "---===endTracks() outputFormat() for Audio startCode=0x"
                    + Integer.toHexString(startCode) + " " + reader + " " + pair.second);
            }
        }
        audioReaders.clear();
        for (int i = 0, l = vobsubReaders.size(); i < l; i++) {
            int startCode = vobsubReaders.keyAt(i);
            ElementaryStreamReader reader = vobsubReaders.get(startCode);
            if (reader != null) {
                readerArray.put(startCode, reader);
                Log.i(TAG, "---===endTracks() outputFormat() for Vobsub startCode=0x"
                    + Integer.toHexString(startCode));
            }
        }
        vobsubReaders.clear();
    }

    @Override
    public void seek() {

    }

    @Override
    public void createTracks(ExtractorOutput extractorOutput, PesReader.TrackIdGenerator idGenerator) {
        //idGenerator.generateNewId();
        //formatId = idGenerator.getFormatId();
        //output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_VIDEO);
        this.extractorOutput = extractorOutput;
        this.idGenerator = idGenerator;
        VideoAttribute videoAttr = getVideoAttribute(vtsVideoAttrBytes);
        for (int i = 0; i < audioReaderNum; i++) {
            AudioAttribute attr = getAudioAttribute(i);
            if (attr == null) {
                continue;
            }
            int position = getAudioPosition(i);
            if (position < 0) {
                continue;
            }
            ElementaryStreamReader reader;
            int startCode;
            String mime;
            switch (attr.format) {
                case 0:
                    startCode = 0x80 + position;
                    reader = new Ac3Reader(attr.lang);
                    mime = MimeTypes.AUDIO_AC3;
                    break;
                case 4:
                    startCode = 0xa0 + position;
                    reader = new DvdPcmReader(attr.lang);
                    mime = MimeTypes.AUDIO_RAW;
                    break;
                case 6:
                    startCode = 0x88 + position;
                    reader = new DtsReader(startCode, attr.lang, attr.roleFlags,
                        DtsReader.EXTSS_HEADER_SIZE_MAX,
                        MimeTypes.VIDEO_PS);
                    mime = MimeTypes.AUDIO_DTS;
                    break;
                default:
                    startCode = -1;
                    reader = null;
                    mime = null;
            }
            if (reader != null) {
                Format format = new Format.Builder()
                    .setContainerMimeType(MimeTypes.VIDEO_PS)
                    .setSampleMimeType(mime)
                    .setChannelCount(attr.channels)
                    .setLanguage(attr.lang)
                    .setRoleFlags(attr.roleFlags)
                    .setSampleRate(attr.frequency > 0 ? attr.frequency : Format.NO_VALUE)
                    .setPcmEncoding(
                        Util.getPcmEncoding(
                            attr.quantization == 20 ? 24 : attr.quantization,
                            ByteOrder.BIG_ENDIAN))
                    .build();
                audioReaders.put(startCode, new Pair(reader, format));
                reader.createTracks(extractorOutput, idGenerator);
            }
        }
        SparseArray<String> languages = new SparseArray<>();
        for (int i = 0; i < vobsubReaderNum; i++) {
            String lang = getSubLang(i);
            List<Integer> positions = getSubpPositions(i, videoAttr);
            for (int position : positions) {
                languages.put(position, lang);
            }
        }
        for (int i = 0, l = languages.size(); i < l; i++) {
            int position = languages.keyAt(i);
            String lang = languages.get(position);
            int width = videoAttr != null ? videoAttr.width : 0;
            int height = videoAttr != null ? videoAttr.height : 0;
            ElementaryStreamReader reader = new VobsubReader(lang, width, height, paletteBytes);
            vobsubReaders.put(0x20 + position, reader);
            reader.createTracks(extractorOutput, idGenerator);
        }
    }

    @Override
    public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {

    }

    @Override
    public void consume(ParsableByteArray data) throws ParserException {

    }

    @Override
    public void packetFinished(boolean isEndOfInput) {

    }

    public ElementaryStreamReader applyForReader(int startCode) {
        Log.i(TAG, "---===createReader() for startCode=0x" + Integer.toHexString(startCode));
        ElementaryStreamReader reader = null;
        if (startCode >= 0x80 && startCode <= 0x87) {
            reader = applyForAudioReader(startCode);
            if (reader == null) {
                reader = new Ac3Reader(null);
            }
        } else if ((startCode >= 0x88 && startCode <= 0x8f) ||
            (startCode >= 0x98 && startCode <= 0x9f)) {
            reader = applyForAudioReader(startCode);
            if (reader == null) {
                reader = new DtsReader(startCode, null, 0,
                    DtsReader.EXTSS_HEADER_SIZE_MAX,
                    MimeTypes.VIDEO_PS);
            }
        } else if (startCode >= 0xa0 && startCode <= 0xaf) {
            reader = applyForAudioReader(startCode);
            if (reader == null) {
                reader = new DvdPcmReader(null);
            }
        } else if (startCode >= 0x20 && startCode <= 0x3f) {
            reader = applyForVobsubReader(startCode);
            if (reader == null) {
                reader = new VobsubReader(null, 0, 0, null);
            }
        } else {
            Log.i(TAG, "---===startCode not handled " + Integer.toHexString(startCode));
        }
        return reader;
    }

    private ElementaryStreamReader applyForAudioReader(int startCode) {
        Pair<ElementaryStreamReader, Format> pair = audioReaders.get(startCode);
        if (pair != null) {
            audioReaders.remove(startCode);
            return pair.first;
        }
        return null;
    }

    private ElementaryStreamReader applyForVobsubReader(int startCode) {
        ElementaryStreamReader reader = vobsubReaders.get(startCode);
        vobsubReaders.remove(startCode);
        return reader;
    }

    private AudioAttribute getAudioAttribute(int index) {
        byte[] bytes = vtsAudioAttrBytes;
        if (bytes != null && bytes.length >= AUDIO_ATTRIB_SIZE * (index + 1)) {
            int base = AUDIO_ATTRIB_SIZE * index;
            //  unsigned char audio_format           : 3;
            //  unsigned char multichannel_extension : 1;
            //  unsigned char lang_type              : 2;
            //  unsigned char application_mode       : 2;
            int format = (bytes[base] & 0b11100000) >> 5;
            int type = (bytes[base] & 0b00001100) >> 2;
            int appMode = bytes[base] & 0b00000011;
            boolean karaoke = appMode == 1;
            boolean surround = appMode == 2;
            //  unsigned char quantization           : 2;
            //  unsigned char sample_frequency       : 2;
            //  unsigned char unknown1               : 1;
            //  unsigned char channels               : 3;
            int quantization = (bytes[base + 1] & 0b11000000) >> 6;
            quantization = quantization == 0 ? 16
                : quantization == 1 ? 20
                : quantization == 2 ? 24
                : quantization == 3 ? /* drc */ 0 : -1;
            int frequency = ((bytes[base + 1] & 0b00110000) >> 4) == 0 ? 48_000 : 0;
            int channels = bytes[base + 1] & 0b00000011 + 1;
            // uint16_t lang_code;
            String lang = type == 1
                && Character.isAlphabetic(bytes[base + 2])
                && Character.isAlphabetic(bytes[base + 3])
                ? new String(bytes, base + 2, 2) : "und";
            // uint8_t  lang_extension;
            // 2: Audio for visually impaired, 3: Director's comments 1, 4: Director's comments 2
            int ext = bytes[base + 4];
            @C.RoleFlags int roleFlags = ext == 2 ? C.ROLE_FLAG_DESCRIBES_VIDEO
                : ext == 3 || ext == 4 ? C.ROLE_FLAG_COMMENTARY : C.ROLE_FLAG_MAIN;
            return new AudioAttribute(
                format, lang, quantization, frequency, channels, karaoke, surround, roleFlags);
        }
        return null;
    }

    private String getSubLang(int index) {
        if (vtsSubpAttrBytes != null && vtsSubpAttrBytes.length >= SUB_ATTRIB_SIZE * (index + 1)) {
            int base = SUB_ATTRIB_SIZE * index;
            // unsigned char type      : 2;
            if ((vtsSubpAttrBytes[base] & 0b00000011) == 1) {
                // uint16_t lang_code;
                if (Character.isAlphabetic(vtsSubpAttrBytes[base + 2])
                    && Character.isAlphabetic(vtsSubpAttrBytes[base + 3])) {
                    return new String(vtsSubpAttrBytes, base + 2, 2);
                }
            }
        }
        return "Vobsub " + (index + 1);
    }

    private int getAudioPosition(int i) {
        if (audioControlBytes == null || i >= audioControlBytes.length / AUDIO_CONTROL_SIZE) {
            return i;
        }
        int from = AUDIO_CONTROL_SIZE * i;
        if ((audioControlBytes[from] & 0x80) == 0) {
            return -1;
        }
        return audioControlBytes[from] & 0x7f;
    }

    private SubpControl getSubpControl(int i) {
        if (subpControlBytes == null || i >= subpControlBytes.length / SUB_CONTROL_SIZE) {
            return null;
        }
        int from = i * SUB_CONTROL_SIZE;
        return new SubpControl(Arrays.copyOfRange(subpControlBytes, from, from + SUB_CONTROL_SIZE));
    }

    private List<Integer> getSubpPositions(int i, VideoAttribute attr) {
        SubpControl control = getSubpControl(i);
        if (control != null && !control.enabled) {
            return List.of();
        }
        return attr != null && control != null ? getSubpPositions(control, attr) : List.of(i);
    }

    private List<Integer> getSubpPositions(@NonNull SubpControl control, @NonNull VideoAttribute attr) {
        if (!control.enabled) {
            return List.of();
        }
        if (attr.displayAspectRatio43) {
            return List.of(control.position43);
        }
        List<Integer> list = new ArrayList<>(List.of(control.positionWS));
        if (attr.letterBoxAllowed) {
            list.add(control.positionLB);
        }
        if (attr.panScanAllowed) {
            list.add(control.positionPS);
        }
        return list;
    }

    private VideoAttribute getVideoAttribute(byte[] bytes) {
        if (bytes == null || bytes.length < 2) {
            return null;
        }
        boolean displayAspectRatio43 = (bytes[0] & 0x0c) == 0;
        boolean letterBoxAllowed = (bytes[0] & 0b00000001) == 0;
        boolean panScanAllowed = (bytes[0] & 0b00000010) == 0;
        int videoFormat = (bytes[0] & 0x30) >> 4;
        int pictureSize = (bytes[1] & 0x0c) >> 2;
        int height = videoFormat != 0 ? 576 : 480;
        int width = 720;
        switch (pictureSize) {
            case 0x0:
                width = 720;
                break;
            case 0x1:
                width = 704;
                break;
            case 0x2:
                width = 352;
                break;
            case 0x3:
                width = 352;
                height /= 2;
                break;
        }
        return new VideoAttribute(
            displayAspectRatio43, letterBoxAllowed, panScanAllowed, height, width);
    }

    private class AudioAttribute {
        private final int format;
        private final String lang;
        private final int quantization;
        private final int frequency;
        private final int channels;
        private final boolean karaoke;
        private final boolean surround;
        private final @C.RoleFlags int roleFlags;

        AudioAttribute(
            int format,
            String lang,
            int quantization,
            int frequency,
            int channels,
            boolean karaoke,
            boolean surround,
            @C.RoleFlags int roleFlags) {
            this.format = format;
            this.lang = lang;
            this.quantization = quantization;
            this.frequency = frequency;
            this.channels = channels;
            this.karaoke = karaoke;
            this.surround = surround;
            this.roleFlags = roleFlags;
        }
    }

    private class VideoAttribute {
        private boolean displayAspectRatio43;
        private final boolean letterBoxAllowed;
        private final boolean panScanAllowed;
        private final int height;
        private final int width;

        VideoAttribute(
            boolean displayAspectRatio43,
            boolean letterBoxAllowed,
            boolean panScanAllowed,
            int height,
            int width) {
            this.displayAspectRatio43 = displayAspectRatio43;
            this.letterBoxAllowed = letterBoxAllowed;
            this.panScanAllowed = panScanAllowed;
            this.height = height;
            this.width = width;
        }
    }

    private class SubpControl {
        boolean enabled;
        int position43, positionWS, positionLB, positionPS;

        // spu_control
        // 0x80000000 - Subtitle enabled
        // 0x1f000000 - Position mask for 4:3 aspect subtitle track
        // 0x001f0000 - Position mask for Wide Screen subtitle track
        // 0x00001f00 - Position mask for Letterbox subtitle track
        // 0x0000001f - Position mask for Pan&Scan subtitle track
        SubpControl(byte[] bytes) {
            enabled = (bytes[0] & 0x80) != 0;
            position43 = bytes[0] & 0x1f;
            positionWS = bytes[1] & 0x1f;
            positionLB = bytes[2] & 0x1f;
            positionPS = bytes[3] & 0x1f;
        }
    }
}
