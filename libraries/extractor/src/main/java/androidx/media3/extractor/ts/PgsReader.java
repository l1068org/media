package androidx.media3.extractor.ts;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import static androidx.media3.extractor.ts.TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR;

public final class PgsReader implements ElementaryStreamReader {

  private final @Nullable String language;
  private final @C.RoleFlags int roleFlags;

  private @MonotonicNonNull TrackOutput output;
  private int sampleBytesWritten;
  private boolean writingSample;
  private long sampleTimeUs;

  public PgsReader(@Nullable String language, @C.RoleFlags int roleFlags) {
    this.language = language;
    this.roleFlags = roleFlags;
    this.sampleTimeUs = C.TIME_UNSET;
  }

  @Override
  public void seek() {
    writingSample = false;
    sampleTimeUs = C.TIME_UNSET;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput,
      TsPayloadReader.TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_TEXT);
    output.format(
        new Format.Builder()
            .setId(idGenerator.getFormatId())
            .setLanguage(language)
            .setRoleFlags(roleFlags)
            .setSampleMimeType(MimeTypes.APPLICATION_PGS)
            .build());
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    if ((flags & FLAG_DATA_ALIGNMENT_INDICATOR) != 0) {
      writingSample = true;
      sampleTimeUs = pesTimeUs;
      sampleBytesWritten = 0;
    }
  }

  @Override
  public void consume(ParsableByteArray data) {
    if (writingSample) {
      int bytesLeft = data.bytesLeft();
      output.sampleData(data, bytesLeft);
      sampleBytesWritten += bytesLeft;
    }
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    if (output != null && sampleTimeUs != C.TIME_UNSET && writingSample) {
      output.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleBytesWritten, 0, null);
      writingSample = false;
    }
  }
}
