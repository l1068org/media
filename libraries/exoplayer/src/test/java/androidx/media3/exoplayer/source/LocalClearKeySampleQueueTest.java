/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.source;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.DrmInitData;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider;
import androidx.media3.exoplayer.drm.DrmSession;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.extractor.TrackOutput.CryptoData;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LocalClearKeySampleQueueTest {
  private static final String LICENSE =
      "{\"keys\":[{\"kty\":\"oct\",\"kid\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"k\":\"K34VFiiu0qar9xWICc9PPA\"}]}";
  private static final String CIPHER_SAMPLE =
      "10000102030405060708090a0b0c0d0e0f7649abac8119b246cee98e9b12e9197d";
  private static final byte[] PLAIN =
      Util.getBytesFromHexString("6bc1bee22e409f96e93d7e117393172a");

  private DrmSessionManager drmSessionManager;
  private SampleQueue queue;
  private final FormatHolder holder = new FormatHolder();
  private final DecoderInputBuffer buffer =
      new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);

  @Before
  public void setUp() {
    MediaItem item =
        new MediaItem.Builder()
            .setUri("https://example.test/manifest.mpd")
            .setDrmConfiguration(
                new MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                    .setLicenseUri(LICENSE)
                    .build())
            .build();
    drmSessionManager = new DefaultDrmSessionManagerProvider().get(item);
    drmSessionManager.setPlayer(Looper.myLooper(), PlayerId.UNSET);
    drmSessionManager.prepare();
    queue =
        SampleQueue.createWithDrm(
            new DefaultAllocator(true, 16),
            drmSessionManager,
            new DrmSessionEventListener.EventDispatcher());
  }

  @After
  public void tearDown() {
    try {
      if (queue != null) {
        queue.release();
      }
    } finally {
      if (drmSessionManager != null) {
        drmSessionManager.release();
      }
    }
  }

  @Test
  public void cbcsFormatAndSamples_areClearDownstreamIncludingPeekAndSeek() throws Exception {
    enqueue(queue, new byte[16]);
    assertThat(queue.read(holder, buffer, 0, false)).isEqualTo(C.RESULT_FORMAT_READ);
    assertClearFormat();
    for (int flags : new int[] {SampleStream.FLAG_PEEK, SampleStream.FLAG_PEEK, 0}) {
      buffer.clear();
      assertThat(queue.read(holder, buffer, flags, false)).isEqualTo(C.RESULT_BUFFER_READ);
      assertClear(buffer);
    }
    assertThat(queue.getReadIndex()).isEqualTo(1);
    assertThat(queue.seekTo(0)).isTrue();
    buffer.clear();
    assertThat(queue.read(holder, buffer, 0, false)).isEqualTo(C.RESULT_BUFFER_READ);
    assertClear(buffer);
    queue.maybeThrowError();
  }

  @Test
  public void formatChanges_switchBetweenCbcsAndClearWithoutReset() throws Exception {
    enqueue(queue, new byte[16]);
    readClearSample();
    queue.format(new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build());
    queue.sampleData(new ParsableByteArray(PLAIN), PLAIN.length);
    queue.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, PLAIN.length, 0, null);
    readClearSample();
    enqueue(queue, new byte[16]);
    readClearSample();
  }

  @Test
  public void reset_canReadSameCbcsFormatAgain() throws Exception {
    enqueue(queue, new byte[16]);
    readClearSample();
    for (boolean resetUpstreamFormat : new boolean[] {false, true}) {
      queue.reset(resetUpstreamFormat);
      enqueue(queue, new byte[16]);
      readClearSample();
    }
  }

  @Test
  public void preRelease_canRecreateDecryptorForSameFormat() throws Exception {
    enqueue(queue, new byte[16]);
    readClearSample();
    queue.preRelease();
    enqueue(queue, new byte[16]);
    readClearSample();
  }

  @Test
  public void omittedSample_isNotDecryptedAndMissingKeyErrorClearsOnReset() throws Exception {
    byte[] missingKey = new byte[16];
    missingKey[0] = 1;
    enqueue(queue, missingKey);
    assertThat(queue.read(holder, buffer, 0, false)).isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(
            queue.read(
                holder, buffer, SampleStream.FLAG_PEEK | SampleStream.FLAG_OMIT_SAMPLE_DATA, false))
        .isEqualTo(C.RESULT_BUFFER_READ);
    queue.maybeThrowError();
    buffer.clear();
    assertThat(queue.read(holder, buffer, 0, false)).isEqualTo(C.RESULT_NOTHING_READ);
    assertThat(queue.getReadIndex()).isEqualTo(0);
    assertThat(queue.isReady(false)).isFalse();
    DrmSession.DrmSessionException error =
        assertThrows(DrmSession.DrmSessionException.class, queue::maybeThrowError);
    assertThat(error.errorCode).isEqualTo(PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR);
    assertThat(queue.read(holder, buffer, 0, false)).isEqualTo(C.RESULT_NOTHING_READ);
    assertThat(assertThrows(DrmSession.DrmSessionException.class, queue::maybeThrowError))
        .isSameInstanceAs(error);
    queue.reset();
    queue.maybeThrowError();
    enqueue(queue, new byte[16]);
    readClearSample();
  }

  private void readClearSample() throws Exception {
    buffer.clear();
    assertThat(queue.read(holder, buffer, SampleStream.FLAG_REQUIRE_FORMAT, false))
        .isEqualTo(C.RESULT_FORMAT_READ);
    assertClearFormat();
    assertThat(queue.read(holder, buffer, 0, false)).isEqualTo(C.RESULT_BUFFER_READ);
    assertClear(buffer);
    queue.maybeThrowError();
  }

  private void assertClearFormat() {
    assertThat(holder.drmSession).isNull();
    assertThat(holder.format.drmInitData).isNull();
    assertThat(holder.format.cryptoType).isEqualTo(C.CRYPTO_TYPE_NONE);
  }

  private static void enqueue(SampleQueue queue, byte[] kid) {
    queue.format(
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setDrmInitData(
                new DrmInitData(
                    "cbcs",
                    new DrmInitData.SchemeData(
                        C.WIDEVINE_UUID, MimeTypes.VIDEO_MP4, new byte[] {1})))
            .build());
    byte[] data = Util.getBytesFromHexString(CIPHER_SAMPLE);
    queue.sampleData(new ParsableByteArray(data), data.length);
    queue.sampleMetadata(
        0,
        C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_ENCRYPTED,
        data.length,
        0,
        new CryptoData(C.CRYPTO_MODE_AES_CBC, kid, 0, 0));
  }

  private static void assertClear(DecoderInputBuffer buffer) {
    assertThat(buffer.isEncrypted()).isFalse();
    ByteBuffer data = buffer.data.duplicate();
    data.flip();
    byte[] bytes = new byte[data.remaining()];
    data.get(bytes);
    assertThat(bytes).isEqualTo(PLAIN);
  }
}
