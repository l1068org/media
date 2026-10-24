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

import android.os.Looper;
import android.util.Base64;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.analytics.PlayerId;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Uses local ClearKeys for CBCS samples and delegates other formats to the platform manager. */
@UnstableApi
public final class LocalClearKeySessionManager implements DrmSessionManager {

  private final DrmSessionManager delegate;
  private final Map<ByteBuffer, SecretKeySpec> keys;

  static DrmSessionManager wrap(
      MediaItem.DrmConfiguration configuration, DrmSessionManager delegate) {
    if (!C.CLEARKEY_UUID.equals(configuration.scheme)
        || configuration.licenseUri == null
        || configuration.getKeySetId() != null) {
      return delegate;
    }
    String response = configuration.licenseUri.toString().trim();
    if (!response.startsWith("{")) {
      return delegate;
    }
    try {
      JSONArray entries = new JSONObject(response).getJSONArray("keys");
      Map<ByteBuffer, SecretKeySpec> keys = new HashMap<>();
      for (int i = 0; i < entries.length(); i++) {
        JSONObject entry = entries.getJSONObject(i);
        byte[] id = Base64.decode(entry.getString("kid"), Base64.URL_SAFE);
        byte[] key = Base64.decode(entry.getString("k"), Base64.URL_SAFE);
        if (!"oct".equals(entry.getString("kty")) || id.length != 16 || key.length != 16) {
          return delegate;
        }
        SecretKeySpec value = new SecretKeySpec(key, "AES");
        SecretKeySpec previous = keys.put(ByteBuffer.wrap(id).asReadOnlyBuffer(), value);
        if (previous != null && !previous.equals(value)) {
          return delegate;
        }
      }
      return keys.isEmpty() ? delegate : new LocalClearKeySessionManager(delegate, keys);
    } catch (JSONException | IllegalArgumentException e) {
      // Preserve the existing DRM error path for invalid local licenses.
      return delegate;
    }
  }

  private LocalClearKeySessionManager(
      DrmSessionManager delegate, Map<ByteBuffer, SecretKeySpec> keys) {
    this.delegate = delegate;
    this.keys = Collections.unmodifiableMap(keys);
  }

  /** Creates a decryptor owned by one sample queue, or null for platform-handled formats. */
  @Nullable
  public ClearKeySampleDecryptor createSampleDecryptor(Format format)
      throws GeneralSecurityException {
    return usesSoftwareDecryption(format) ? new ClearKeySampleDecryptor(keys) : null;
  }

  private static boolean usesSoftwareDecryption(Format format) {
    return format.drmInitData != null && C.CENC_TYPE_cbcs.equals(format.drmInitData.schemeType);
  }

  @Override
  public void prepare() {
    delegate.prepare();
  }

  @Override
  public void release() {
    delegate.release();
  }

  @Override
  public void setPlayer(Looper playbackLooper, PlayerId playerId) {
    delegate.setPlayer(playbackLooper, playerId);
  }

  @Override
  public DrmSessionReference preacquireSession(
      @Nullable DrmSessionEventListener.EventDispatcher dispatcher, Format format) {
    return usesSoftwareDecryption(format)
        ? DrmSessionReference.EMPTY
        : delegate.preacquireSession(dispatcher, format);
  }

  @Override
  @Nullable
  public DrmSession acquireSession(
      @Nullable DrmSessionEventListener.EventDispatcher dispatcher, Format format) {
    return usesSoftwareDecryption(format) ? null : delegate.acquireSession(dispatcher, format);
  }

  @Override
  public @C.CryptoType int getCryptoType(Format format) {
    return usesSoftwareDecryption(format) ? C.CRYPTO_TYPE_NONE : delegate.getCryptoType(format);
  }

  @Override
  public Format getCryptoFormat(Format format) {
    return usesSoftwareDecryption(format)
        ? format.buildUpon().setDrmInitData(null).setCryptoType(C.CRYPTO_TYPE_NONE).build()
        : delegate.getCryptoFormat(format);
  }
}
