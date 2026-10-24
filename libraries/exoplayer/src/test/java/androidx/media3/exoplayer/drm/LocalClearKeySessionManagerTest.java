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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.DrmInitData;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LocalClearKeySessionManagerTest {
  private static final String KEY_ENTRY =
      "{\"kty\":\"oct\",\"kid\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"k\":\"K34VFiiu0qar9xWICc9PPA\"}";
  private static final String LICENSE = "{\"keys\":[" + KEY_ENTRY + "]}";

  @Test
  public void localCbcs_usesSoftwareWithoutAcquiringPlatformSession() throws Exception {
    DrmSessionManager delegate = mock(DrmSessionManager.class);
    DrmSessionManager manager =
        LocalClearKeySessionManager.wrap(config(C.CLEARKEY_UUID, LICENSE), delegate);
    Format cbcs = format("cbcs");
    assertThat(manager.getCryptoType(cbcs)).isEqualTo(C.CRYPTO_TYPE_NONE);
    assertThat(manager.getCryptoFormat(cbcs).cryptoType).isEqualTo(C.CRYPTO_TYPE_NONE);
    assertThat(manager.getCryptoFormat(cbcs).drmInitData).isNull();
    assertThat(cbcs.drmInitData.schemeType).isEqualTo("cbcs");
    assertThat(manager.acquireSession(null, cbcs)).isNull();
    assertThat(manager.preacquireSession(null, cbcs))
        .isSameInstanceAs(DrmSessionManager.DrmSessionReference.EMPTY);
    assertThat(((LocalClearKeySessionManager) manager).createSampleDecryptor(cbcs)).isNotNull();
    verifyNoInteractions(delegate);
  }

  @Test
  public void cencAndClearFormats_useOriginalManager() throws Exception {
    for (Format format :
        new Format[] {
          format("cenc"), format("cens"), format("cbc1"), new Format.Builder().build()
        }) {
      DrmSessionManager delegate = mock(DrmSessionManager.class);
      DrmSession session = mock(DrmSession.class);
      DrmSessionManager.DrmSessionReference reference =
          mock(DrmSessionManager.DrmSessionReference.class);
      Format exposedFormat = format.copyWithCryptoType(C.CRYPTO_TYPE_FRAMEWORK);
      when(delegate.getCryptoType(format)).thenReturn(C.CRYPTO_TYPE_FRAMEWORK);
      when(delegate.getCryptoFormat(format)).thenReturn(exposedFormat);
      when(delegate.acquireSession(null, format)).thenReturn(session);
      when(delegate.preacquireSession(null, format)).thenReturn(reference);
      DrmSessionManager manager =
          LocalClearKeySessionManager.wrap(config(C.CLEARKEY_UUID, LICENSE), delegate);
      assertThat(manager.getCryptoType(format)).isEqualTo(C.CRYPTO_TYPE_FRAMEWORK);
      assertThat(manager.getCryptoFormat(format)).isSameInstanceAs(exposedFormat);
      assertThat(manager.acquireSession(null, format)).isSameInstanceAs(session);
      assertThat(manager.preacquireSession(null, format)).isSameInstanceAs(reference);
      assertThat(((LocalClearKeySessionManager) manager).createSampleDecryptor(format)).isNull();
    }
  }

  @Test
  public void lifecycle_isDelegated() {
    DrmSessionManager delegate = mock(DrmSessionManager.class);
    DrmSessionManager manager =
        LocalClearKeySessionManager.wrap(config(C.CLEARKEY_UUID, LICENSE), delegate);
    Looper looper = Looper.getMainLooper();
    manager.setPlayer(looper, PlayerId.UNSET);
    manager.prepare();
    manager.release();
    verify(delegate).setPlayer(looper, PlayerId.UNSET);
    verify(delegate).prepare();
    verify(delegate).release();
    verifyNoMoreInteractions(delegate);
  }

  @Test
  public void duplicateIdenticalKeys_areAccepted() {
    DrmSessionManager delegate = mock(DrmSessionManager.class);
    String license = "{\"keys\":[" + KEY_ENTRY + "," + KEY_ENTRY + "]}";
    assertThat(LocalClearKeySessionManager.wrap(config(C.CLEARKEY_UUID, license), delegate))
        .isInstanceOf(LocalClearKeySessionManager.class);
  }

  @Test
  public void conflictingKeysForSameId_areNotWrapped() {
    DrmSessionManager delegate = mock(DrmSessionManager.class);
    String otherKey = KEY_ENTRY.replace("K34VFiiu0qar9xWICc9PPA", "AAAAAAAAAAAAAAAAAAAAAA");
    String license = "{\"keys\":[" + KEY_ENTRY + "," + otherKey + "]}";
    assertThat(LocalClearKeySessionManager.wrap(config(C.CLEARKEY_UUID, license), delegate))
        .isSameInstanceAs(delegate);
  }

  @Test
  public void remoteWidevineOfflineAndInvalidLicenses_areNotWrapped() {
    DrmSessionManager delegate = mock(DrmSessionManager.class);
    MediaItem.DrmConfiguration[] configurations = {
      config(C.CLEARKEY_UUID, "https://example.test/license"),
      config(C.WIDEVINE_UUID, LICENSE),
      new MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
          .setLicenseUri(LICENSE)
          .setKeySetId(new byte[] {1})
          .build(),
      config(C.CLEARKEY_UUID, "{invalid}"),
      config(C.CLEARKEY_UUID, "{\"keys\":[]}"),
      config(C.CLEARKEY_UUID, LICENSE.replace("K34VFiiu0qar9xWICc9PPA", "AA")),
      config(C.CLEARKEY_UUID, LICENSE.replace("oct", "RSA"))
    };
    for (MediaItem.DrmConfiguration configuration : configurations) {
      assertThat(LocalClearKeySessionManager.wrap(configuration, delegate))
          .isSameInstanceAs(delegate);
    }
  }

  private static MediaItem.DrmConfiguration config(UUID scheme, String license) {
    return new MediaItem.DrmConfiguration.Builder(scheme).setLicenseUri(license).build();
  }

  private static Format format(String scheme) {
    return new Format.Builder().setDrmInitData(new DrmInitData(scheme)).build();
  }
}
