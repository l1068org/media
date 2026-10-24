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
package androidx.media3.exoplayer.drm;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;
import org.junit.runner.RunWith;

/** CBC known-answer vectors from NIST SP 800-38A, with CBCS subsample/pattern layouts. */
@RunWith(AndroidJUnit4.class)
public class ClearKeySampleDecryptorTest {
  private static final String KEY = "2b7e151628aed2a6abf7158809cf4f3c";
  private static final String IV = "000102030405060708090a0b0c0d0e0f";
  private static final String C1 = "7649abac8119b246cee98e9b12e9197d";
  private static final String C2 = "5086cb9b507219ee95db113a917678b2";
  private static final String C3 = "73bed6b8e3c1743b7116e69e22229516";
  private static final String P1 = "6bc1bee22e409f96e93d7e117393172a";
  private static final String P2 = "ae2d8a571e03ac9c9eb76fac45af8e51";
  private static final String P3 = "30c81c46a35ce411e5fbc1191a0a52ef";
  private static final byte[] KID = new byte[16];

  @Test
  public void fullSample_decryptsCompleteBlocksAndPreservesTailAndBufferPrefix() throws Exception {
    DecoderInputBuffer buffer =
        sample("fefe" + C1 + C2 + "ff", new int[] {0}, new int[] {33}, 0, 0);
    int position = buffer.data.position();
    int limit = buffer.data.limit();
    decryptor().decrypt(buffer, 2);
    assertThat(bytes(buffer)).isEqualTo(hex("fefe" + P1 + P2 + "ff"));
    assertThat(buffer.data.position()).isEqualTo(position);
    assertThat(buffer.data.limit()).isEqualTo(limit);
    assertThat(buffer.isEncrypted()).isFalse();
  }

  @Test
  public void pattern_skipsClearBlocksWithoutUpdatingCbcState() throws Exception {
    DecoderInputBuffer buffer = sample(C1 + P1 + C2 + "ff", new int[] {0}, new int[] {49}, 1, 1);
    decryptor().decrypt(buffer, 0);
    assertThat(bytes(buffer)).isEqualTo(hex(P1 + P1 + P2 + "ff"));
  }

  @Test
  public void subsamples_restartIvAndPattern() throws Exception {
    DecoderInputBuffer buffer =
        sample("ffee" + C1 + "dd" + C1, new int[] {2, 1}, new int[] {16, 16}, 1, 9);
    decryptor().decrypt(buffer, 0);
    assertThat(bytes(buffer)).isEqualTo(hex("ffee" + P1 + "dd" + P1));
  }

  @Test
  public void clearOnlySubsample_doesNotAffectNextSubsampleIv() throws Exception {
    DecoderInputBuffer buffer =
        sample("ffee" + C1 + C1, new int[] {2, 0, 0}, new int[] {0, 16, 16}, 1, 9);
    decryptor().decrypt(buffer, 0);
    assertThat(bytes(buffer)).isEqualTo(hex("ffee" + P1 + P1));
  }

  @Test
  public void zeroCryptBlocks_preservesAllPatternData() throws Exception {
    DecoderInputBuffer buffer = sample(P1 + P2 + "ff", new int[] {0}, new int[] {33}, 0, 1);
    decryptor().decrypt(buffer, 0);
    assertThat(bytes(buffer)).isEqualTo(hex(P1 + P2 + "ff"));
    assertThat(buffer.isEncrypted()).isFalse();
  }

  @Test
  public void incompleteCryptRun_decryptsCompleteBlocksAfterSkippedBlocks() throws Exception {
    DecoderInputBuffer buffer =
        sample(C1 + C2 + P1 + C3 + "ff", new int[] {0}, new int[] {65}, 2, 1);
    decryptor().decrypt(buffer, 0);
    assertThat(bytes(buffer)).isEqualTo(hex(P1 + P2 + P1 + P3 + "ff"));
    assertThat(buffer.isEncrypted()).isFalse();
  }

  @Test
  public void shortProtectedRange_decryptsCompleteBlocks() throws Exception {
    for (int cryptBlocks = 2; cryptBlocks <= 15; cryptBlocks++) {
      DecoderInputBuffer buffer = sample(C1 + "ff", new int[] {0}, new int[] {17}, cryptBlocks, 9);
      decryptor().decrypt(buffer, 0);
      assertThat(bytes(buffer)).isEqualTo(hex(P1 + "ff"));
    }
  }

  @Test
  public void noSkippedBlocks_decryptsIncompleteCryptRun() throws Exception {
    DecoderInputBuffer buffer = sample(C1 + C2 + C3 + "ff", new int[] {0}, new int[] {49}, 2, 0);
    decryptor().decrypt(buffer, 0);
    assertThat(bytes(buffer)).isEqualTo(hex(P1 + P2 + P3 + "ff"));
  }

  @Test
  public void subsamples_incompleteCryptRuns_restartIvAndPattern() throws Exception {
    DecoderInputBuffer buffer =
        sample("ffee" + C1 + "ffdd" + C1, new int[] {2, 1}, new int[] {17, 16}, 2, 9);
    decryptor().decrypt(buffer, 0);
    assertThat(bytes(buffer)).isEqualTo(hex("ffee" + P1 + "ffdd" + P1));
  }

  @Test
  public void partialBlock_remainsClear() throws Exception {
    for (int size = 1; size < 16; size++) {
      String tail = P1.substring(0, size * 2);
      DecoderInputBuffer buffer = sample(tail, new int[] {0}, new int[] {size}, 2, 1);
      decryptor().decrypt(buffer, 0);
      assertThat(bytes(buffer)).isEqualTo(hex(tail));
      assertThat(buffer.isEncrypted()).isFalse();
    }
  }

  @Test
  public void keyRotation_selectsKeyForEachSampleAndResetsIv() throws Exception {
    Map<ByteBuffer, SecretKeySpec> keys = keys();
    byte[] secondId = hex("01000000000000000000000000000000");
    keys.put(ByteBuffer.wrap(secondId), new SecretKeySpec(new byte[16], "AES"));
    ClearKeySampleDecryptor decryptor = new ClearKeySampleDecryptor(keys);
    DecoderInputBuffer first = sample(C1, new int[] {0}, new int[] {16}, 0, 0);
    decryptor.decrypt(first, 0);
    DecoderInputBuffer second =
        sample("66e94bd4ef8a2c3b884cfa59ca342b2e", new int[] {0}, new int[] {16}, 0, 0);
    second.cryptoInfo.key = secondId;
    second.cryptoInfo.iv = new byte[16];
    decryptor.decrypt(second, 0);
    assertThat(bytes(first)).isEqualTo(hex(P1));
    assertThat(bytes(second)).isEqualTo(new byte[16]);
  }

  @Test
  public void missingKey_failsWithoutExposingCiphertextAsClear() throws Exception {
    DecoderInputBuffer buffer = sample(C1, new int[] {0}, new int[] {16}, 0, 0);
    buffer.cryptoInfo.key = hex("01000000000000000000000000000000");
    assertThrows(GeneralSecurityException.class, () -> decryptor().decrypt(buffer, 0));
    assertThat(buffer.isEncrypted()).isTrue();
    assertThat(bytes(buffer)).isEqualTo(hex(C1));
  }

  @Test
  public void malformedSubsamples_rejectedBeforeBufferMutation() throws Exception {
    for (int[] sizes : new int[][] {new int[0], {-1}, {15}, {17}, {Integer.MAX_VALUE}}) {
      DecoderInputBuffer buffer = sample(C1, new int[] {0}, sizes, 0, 0);
      assertThrows(GeneralSecurityException.class, () -> decryptor().decrypt(buffer, 0));
      assertThat(buffer.isEncrypted()).isTrue();
      assertThat(bytes(buffer)).isEqualTo(hex(C1));
    }
  }

  @Test
  public void ctrMode_rejectedByCbcsDecryptor() throws Exception {
    DecoderInputBuffer buffer = sample(C1, new int[] {0}, new int[] {16}, 0, 0);
    buffer.cryptoInfo.mode = C.CRYPTO_MODE_AES_CTR;
    assertThrows(GeneralSecurityException.class, () -> decryptor().decrypt(buffer, 0));
  }

  @Test
  public void clearBuffer_isNotModified() throws Exception {
    DecoderInputBuffer buffer = sample(C1, new int[] {0}, new int[] {16}, 0, 0);
    buffer.clearFlag(C.BUFFER_FLAG_ENCRYPTED);
    decryptor().decrypt(buffer, 0);
    assertThat(bytes(buffer)).isEqualTo(hex(C1));
  }

  private static ClearKeySampleDecryptor decryptor() throws Exception {
    return new ClearKeySampleDecryptor(keys());
  }

  private static Map<ByteBuffer, SecretKeySpec> keys() {
    Map<ByteBuffer, SecretKeySpec> keys = new HashMap<>();
    keys.put(ByteBuffer.wrap(KID), new SecretKeySpec(hex(KEY), "AES"));
    return keys;
  }

  private static DecoderInputBuffer sample(
      String bytes, int[] clear, int[] encrypted, int cryptBlocks, int skipBlocks) {
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    byte[] data = hex(bytes);
    buffer.ensureSpaceForWrite(data.length);
    buffer.data.put(data);
    buffer.addFlag(C.BUFFER_FLAG_ENCRYPTED);
    buffer.cryptoInfo.set(
        clear.length,
        clear,
        encrypted,
        KID,
        hex(IV),
        C.CRYPTO_MODE_AES_CBC,
        cryptBlocks,
        skipBlocks);
    return buffer;
  }

  private static byte[] bytes(DecoderInputBuffer buffer) {
    ByteBuffer data = buffer.data.duplicate();
    data.flip();
    byte[] result = new byte[data.remaining()];
    data.get(result);
    return result;
  }

  private static byte[] hex(String value) {
    return Util.getBytesFromHexString(value);
  }
}
