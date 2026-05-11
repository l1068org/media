package androidx.media3.exoplayer.upstream;

import androidx.annotation.Nullable;

final class DefaultAllocatorNative {

  private static final String LIBRARY_NAME = "media3_exoplayer_jni";

  private static boolean loadAttempted;
  private static boolean isAvailable;

  @Nullable
  public static Allocation createAllocation(int size) {
    if (!isAvailable()) {
      return null;
    }
    try {
      return nativeCreateAllocation(size);
    } catch (UnsatisfiedLinkError e) {
      isAvailable = false;
      return null;
    }
  }

  public static void freeAllocation(Allocation allocation) {
    long nativeHandle = allocation.nativeHandle;
    if (nativeHandle == 0 || !isAvailable()) {
      return;
    }
    try {
      nativeFreeAllocation(nativeHandle);
      allocation.nativeHandle = 0;
    } catch (UnsatisfiedLinkError e) {
      isAvailable = false;
    }
  }

  private static synchronized boolean isAvailable() {
    if (loadAttempted) {
      return isAvailable;
    }
    loadAttempted = true;
    try {
      System.loadLibrary(LIBRARY_NAME);
      isAvailable = true;
    } catch (SecurityException | UnsatisfiedLinkError e) {
      isAvailable = false;
    }
    return isAvailable;
  }

  private static native Allocation nativeCreateAllocation(int size);

  private static native void nativeFreeAllocation(long handle);

  private DefaultAllocatorNative() {}
}
