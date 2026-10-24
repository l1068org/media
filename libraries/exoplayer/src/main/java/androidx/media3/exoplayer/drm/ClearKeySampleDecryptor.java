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

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoInfo;
import androidx.media3.decoder.DecoderInputBuffer;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Decrypts CBCS samples with explicitly supplied local ClearKeys on the playback thread. */
@UnstableApi
public final class ClearKeySampleDecryptor {

  private final Map<ByteBuffer, SecretKeySpec> keys;
  private final Cipher cipher;
  private byte[] scratch = new byte[0];

  ClearKeySampleDecryptor(Map<ByteBuffer, SecretKeySpec> keys) throws GeneralSecurityException {
    this.keys = keys;
    cipher = Cipher.getInstance("AES/CBC/NoPadding");
  }

  /**
   * Decrypts only the sample appended at {@code offset}, preserving buffer position and limit. The
   * buffer must still be in write mode. Queued data remains encrypted, including on peek reads.
   */
  public void decrypt(DecoderInputBuffer buffer, int offset) throws GeneralSecurityException {
    if (!buffer.isEncrypted()) {
      return;
    }
    CryptoInfo info = buffer.cryptoInfo;
    if (buffer.data == null
        || info.mode != C.CRYPTO_MODE_AES_CBC
        || info.key == null
        || info.key.length != 16
        || info.iv == null
        || info.iv.length != 16
        || info.numSubSamples <= 0
        || info.encryptedBlocks < 0
        || info.encryptedBlocks > 15
        || info.clearBlocks < 0
        || info.clearBlocks > 15) {
      throw new GeneralSecurityException("Invalid CBCS sample encryption metadata");
    }
    SecretKeySpec key = keys.get(ByteBuffer.wrap(info.key));
    if (key == null) {
      throw new GeneralSecurityException("No local ClearKey for the sample key ID");
    }
    int size = buffer.data.position() - offset;
    if (offset < 0 || size < 0) {
      throw new GeneralSecurityException("Invalid CBCS sample offset");
    }
    long total = 0;
    for (int i = 0; i < info.numSubSamples; i++) {
      total += subsampleSize(info.numBytesOfClearData, i);
      total += subsampleSize(info.numBytesOfEncryptedData, i);
      if (total > size) {
        throw new GeneralSecurityException("CBCS subsamples exceed the sample size");
      }
    }
    if (total != size) {
      throw new GeneralSecurityException("CBCS subsamples do not cover the sample");
    }
    if (scratch.length < size) {
      scratch = new byte[size];
    }
    ByteBuffer data = buffer.data.duplicate();
    data.limit(buffer.data.position()).position(offset);
    data.get(scratch, 0, size);
    IvParameterSpec iv = new IvParameterSpec(info.iv);
    int cursor = 0;
    for (int i = 0; i < info.numSubSamples; i++) {
      cursor += subsampleSize(info.numBytesOfClearData, i);
      int protectedSize = subsampleSize(info.numBytesOfEncryptedData, i);
      // CBCS restarts both the IV and the encryption pattern at each subsample.
      cipher.init(Cipher.DECRYPT_MODE, key, iv);
      if (info.encryptedBlocks == 0 && info.clearBlocks == 0) {
        decryptBlocks(cursor, protectedSize / 16 * 16);
      } else if (info.encryptedBlocks != 0) {
        // Only complete AES blocks participate in the pattern.
        int end = cursor + protectedSize / 16 * 16;
        int encryptedBytes = info.encryptedBlocks * 16;
        int position = cursor;
        while (position < end) {
          int bytesToDecrypt = Math.min(encryptedBytes, end - position);
          decryptBlocks(position, bytesToDecrypt);
          position += bytesToDecrypt;
          // Skipped blocks do not update the CBC chaining state.
          position += Math.min(info.clearBlocks * 16, end - position);
        }
      }
      cursor += protectedSize;
    }
    data.position(offset);
    data.put(scratch, 0, size);
    buffer.clearFlag(C.BUFFER_FLAG_ENCRYPTED);
  }

  private void decryptBlocks(int offset, int size) throws GeneralSecurityException {
    if (size != 0 && cipher.update(scratch, offset, size, scratch, offset) != size) {
      throw new GeneralSecurityException("Unexpected CBCS decrypted size");
    }
  }

  private static int subsampleSize(@Nullable int[] sizes, int index)
      throws GeneralSecurityException {
    if (sizes == null) {
      return 0;
    }
    if (index >= sizes.length || sizes[index] < 0) {
      throw new GeneralSecurityException("Invalid CBCS subsample size");
    }
    return sizes[index];
  }
}
