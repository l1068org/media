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
package androidx.media3.exoplayer.upstream;

import static java.lang.Math.max;

import androidx.annotation.Nullable;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Default implementation of {@link Allocator}. */
@UnstableApi
public final class DefaultAllocator implements Allocator {

  private static final int AVAILABLE_EXTRA_CAPACITY = 100;

 // Tracks buffers created via mmap (FileChannel.map, Os.mmap, SharedMemory) so we only
 // munmap those, not buffers from allocateDirect() which use malloc internally.
 private static final Set<java.nio.ByteBuffer> mmapTrackedBuffers =
     Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

 // Cached reflection fields — looked up once, reused on every buffer creation/free.
 // This eliminates the expensive getDeclaredField + setAccessible overhead per call.
 private static final class ReflectionCache {
   static final java.lang.reflect.Field bufferAddress;
   static final java.lang.reflect.Field bufferCapacity;
   static final java.lang.reflect.Field bufferBlock; // ojluni MemoryBlock, may be null
   static final java.lang.reflect.Field effectiveDirectAddress; // luni int, may be null
   static final java.lang.reflect.Method madviseMethod; // Os.madvise, may be null (API 23+)
   static final boolean isOjluni; // true if Buffer.address is long (API 24+)

   static {
     java.lang.reflect.Field addr = null;
     java.lang.reflect.Field cap = null;
     java.lang.reflect.Field blk = null;
     java.lang.reflect.Field eda = null;
     java.lang.reflect.Method madvise = null;
     boolean ojluni = false;
     try {
       addr = java.nio.Buffer.class.getDeclaredField("address");
       addr.setAccessible(true);
       ojluni = (addr.getType() == long.class);
     } catch (Exception e) {
       // address field not found
     }
     try {
       cap = java.nio.Buffer.class.getDeclaredField("capacity");
       cap.setAccessible(true);
     } catch (Exception e) {
       // capacity field not found
     }
     try {
       blk = java.nio.Buffer.class.getDeclaredField("block");
       blk.setAccessible(true);
     } catch (Exception e) {
       // block field not found (luni or different ART version)
     }
     try {
       eda = java.nio.Buffer.class.getDeclaredField("effectiveDirectAddress");
       eda.setAccessible(true);
     } catch (Exception e) {
       // effectiveDirectAddress field not found
     }
     try {
       madvise = android.system.Os.class.getMethod("madvise", long.class, long.class, int.class);
     } catch (Exception e) {
       // Os.madvise not available (pre-API 23)
     }
     bufferAddress = addr;
     bufferCapacity = cap;
     bufferBlock = blk;
     effectiveDirectAddress = eda;
     madviseMethod = madvise;
     isOjluni = ojluni;
   }
 }

  private final boolean trimOnReset;
  private final int individualAllocationSize;
  @Nullable private final byte[] initialAllocationBlock;

  private int targetBufferSize;
  private int allocatedCount;
  private int availableCount;
  private @NullableType Allocation[] availableAllocations;

  /**
   * Constructs an instance without creating any {@link Allocation}s up front.
   *
   * @param trimOnReset Whether memory is freed when the allocator is reset. Should be true unless
   *     the allocator will be re-used by multiple player instances. If set to false, trimming can
   *     be forced by calling {@link #setTargetBufferSize(int)} manually when required.
   * @param individualAllocationSize The length of each individual {@link Allocation}.
   */
  public DefaultAllocator(boolean trimOnReset, int individualAllocationSize) {
    this(trimOnReset, individualAllocationSize, 0);
  }

  /**
   * Constructs an instance with some {@link Allocation}s created up front.
   *
   * <p>Note: {@link Allocation}s created up front will never be discarded by {@link #trim()}.
   *
   * @param trimOnReset Whether memory is freed when the allocator is reset. Should be true unless
   *     the allocator will be re-used by multiple player instances. If set to false, trimming can
   *     be forced by calling {@link #setTargetBufferSize(int)} manually when required.
   * @param individualAllocationSize The length of each individual {@link Allocation}.
   * @param initialAllocationCount The number of allocations to create up front.
   */
  public DefaultAllocator(
      boolean trimOnReset, int individualAllocationSize, int initialAllocationCount) {
    Assertions.checkArgument(individualAllocationSize > 0);
    Assertions.checkArgument(initialAllocationCount >= 0);
    this.trimOnReset = trimOnReset;
    this.individualAllocationSize = individualAllocationSize;
    this.availableCount = initialAllocationCount;
    this.availableAllocations = new Allocation[initialAllocationCount + AVAILABLE_EXTRA_CAPACITY];
    if (initialAllocationCount > 0) {
      initialAllocationBlock = null;
      for (int i = 0; i < initialAllocationCount; i++) {
        availableAllocations[i] = new Allocation(createUntrackedBuffer(individualAllocationSize), 0);
      }
    } else {
      initialAllocationBlock = null;
    }
  }

  public synchronized void reset() {
    if (trimOnReset) {
      setTargetBufferSize(0);
    }
  }

  public synchronized void setTargetBufferSize(int targetBufferSize) {
    boolean targetBufferSizeReduced = targetBufferSize < this.targetBufferSize;
    this.targetBufferSize = targetBufferSize;
    if (targetBufferSizeReduced) {
      trim();
    }
  }

  @Override
  public synchronized Allocation allocate() {
    allocatedCount++;
    Allocation allocation;
    if (availableCount > 0) {
      allocation = Assertions.checkNotNull(availableAllocations[--availableCount]);
      availableAllocations[availableCount] = null;
    } else {
      allocation = new Allocation(createUntrackedBuffer(individualAllocationSize), 0);
      if (allocatedCount > availableAllocations.length) {
        // Make availableAllocations be large enough to contain all allocations made by this
        // allocator so that release() does not need to grow the availableAllocations array. See
        // [Internal ref: b/209801945].
        availableAllocations = Arrays.copyOf(availableAllocations, availableAllocations.length * 2);
      }
    }
    return allocation;
  }

  @Override
  public synchronized void release(Allocation allocation) {
    availableAllocations[availableCount++] = allocation;
    allocatedCount--;
    // Wake up threads waiting for the allocated size to drop.
    notifyAll();
  }

  @Override
  public synchronized void release(@Nullable AllocationNode allocationNode) {
    while (allocationNode != null) {
      availableAllocations[availableCount++] = allocationNode.getAllocation();
      allocatedCount--;
      allocationNode = allocationNode.next();
    }
    // Wake up threads waiting for the allocated size to drop.
    notifyAll();
  }

  @Override
  public synchronized void trim() {
    int targetAllocationCount = Util.ceilDivide(targetBufferSize, individualAllocationSize);
    int targetAvailableCount = max(0, targetAllocationCount - allocatedCount);
    if (targetAvailableCount >= availableCount) {
      // We're already at or below the target.
      return;
    }

    if (initialAllocationBlock != null) {
      // Some allocations are backed by an initial block. We need to make sure that we hold onto all
      // such allocations. Re-order the available allocations so that the ones backed by the initial
      // block come first.
      int lowIndex = 0;
      int highIndex = availableCount - 1;
      while (lowIndex <= highIndex) {
        Allocation lowAllocation = Assertions.checkNotNull(availableAllocations[lowIndex]);
        if (lowAllocation.data == initialAllocationBlock) {
          lowIndex++;
        } else {
          Allocation highAllocation = Assertions.checkNotNull(availableAllocations[highIndex]);
          if (highAllocation.data != initialAllocationBlock) {
            highIndex--;
          } else {
            availableAllocations[lowIndex++] = highAllocation;
            availableAllocations[highIndex--] = lowAllocation;
          }
        }
      }
      // lowIndex is the index of the first allocation not backed by an initial block.
      targetAvailableCount = max(targetAvailableCount, lowIndex);
      if (targetAvailableCount >= availableCount) {
        // We're already at or below the target.
        return;
      }
    }

    // Discard allocations beyond the target.
    for (int i = targetAvailableCount; i < availableCount; i++) {
      Allocation allocation = availableAllocations[i];
      if (allocation != null && allocation.buffer != null) {
        freeUntrackedBuffer(allocation.buffer);
      }
    }
    Arrays.fill(availableAllocations, targetAvailableCount, availableCount, null);
    availableCount = targetAvailableCount;
  }

  @Override
  public synchronized int getTotalBytesAllocated() {
    return allocatedCount * individualAllocationSize;
  }

  public synchronized int getAvailableBytes() {
    return availableCount * individualAllocationSize;
  }

  public synchronized int getMemoryFootprint() {
    return (allocatedCount + availableCount) * individualAllocationSize;
  }

  @Override
  public int getIndividualAllocationLength() {
    return individualAllocationSize;
  }

  private static java.nio.ByteBuffer createUntrackedBuffer(int size) {
    // API 27+: Direct anonymous mmap — single syscall, no fd overhead, page-aligned.
    // Faster than SharedMemory (ashmem fd → map → close = 3 syscalls).
    if (android.os.Build.VERSION.SDK_INT >= 27) {
      try {
        long address = android.system.Os.mmap(
            0, size,
            android.system.OsConstants.PROT_READ | android.system.OsConstants.PROT_WRITE,
            android.system.OsConstants.MAP_PRIVATE | android.system.OsConstants.MAP_ANONYMOUS,
            null, 0);
        // Hint to kernel: sequential access → aggressive readahead, reduced page fault latency.
        // Os.madvise is only available from API 23+, so use reflection for compatibility.
        try {
          if (android.os.Build.VERSION.SDK_INT >= 23) {
            madvise(address, size, 2 /* MADV_SEQUENTIAL */);
          }
        } catch (Exception ignored) {
          // madvise is optional
        }
        // Wrap the mmap'd native memory in a DirectByteBuffer via reflection.
        // This avoids the SharedMemory fd overhead while keeping ByteBuffer API compatibility.
        java.nio.ByteBuffer buffer = newDirectByteBuffer(address, size);
        if (buffer != null) {
          mmapTrackedBuffers.add(buffer);
          return buffer;
        }
        // newDirectByteBuffer failed, munmap and fall through
        try { android.system.Os.munmap(address, size); } catch (Exception ignored) {}
      } catch (Exception e) {
        // Direct mmap failed, fall through to SharedMemory
      }

      // SharedMemory fallback for API 27+
      android.os.SharedMemory sharedMemory = null;
      try {
        sharedMemory = android.os.SharedMemory.create(null, size);
        java.nio.ByteBuffer buffer = sharedMemory.mapReadWrite();
        // Apply sequential access hint to SharedMemory-backed mapping too.
        try {
          if (android.os.Build.VERSION.SDK_INT >= 23) {
            long addr = getNativeAddress(buffer);
            if (addr != 0) {
              madvise(addr, size, 2 /* MADV_SEQUENTIAL */);
            }
          }
        } catch (Exception ignored) {
        }
        mmapTrackedBuffers.add(buffer);
        return buffer;
      } catch (Exception e) {
        // SharedMemory creation or mapping failed, fall through
      } finally {
        if (sharedMemory != null) {
          try {
            sharedMemory.close();
          } catch (Exception ignored) {
          }
        }
      }
    }

    // API < 27 Fallback: Untracked Memory Mapped File (mmap)
    try {
      java.io.File tempFile = java.io.File.createTempFile("exo_buf_", ".tmp");
      try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(tempFile, "rw")) {
        raf.setLength(size);
        java.nio.MappedByteBuffer buffer = raf.getChannel().map(java.nio.channels.FileChannel.MapMode.READ_WRITE, 0, size);
        mmapTrackedBuffers.add(buffer);
        return buffer;
      } finally {
        // Unlink the file immediately. The OS will keep the anonymous memory mapping alive.
        tempFile.delete();
      }
    } catch (Exception e) {
      // Last resort fallback
    }

    return java.nio.ByteBuffer.allocateDirect(size);
  }
  private static void freeUntrackedBuffer(java.nio.ByteBuffer buffer) {
    // All mmap'd buffers (anonymous, SharedMemory, FileChannel) are tracked in mmapTrackedBuffers.
    // Only munmap those — allocateDirect() uses malloc internally, munmap on malloc'd memory = crash.
    if (mmapTrackedBuffers.remove(buffer)) {
      long address = getNativeAddress(buffer);
      if (address != 0) {
        try {
          android.system.Os.munmap(address, buffer.capacity());
        } catch (Exception e) {
          // munmap failed, GC will reclaim eventually
        }
      }
      return;
    }

    // allocateDirect() or munmap failed: let GC reclaim the native memory eventually.
    // Android ART's DirectByteBuffer has no accessible cleaner, so explicit free is not possible.
  }

  /**
   * Reads the native memory address from a direct ByteBuffer via reflection.
   *
   * <p>Android has two NIO implementations:
   *
   * <ul>
   *   <li>API 24+ (ojluni/OpenJDK): {@code java.nio.Buffer.address} is {@code long}
   *   <li>API 21-23 (luni/Harmony): {@code java.nio.Buffer.effectiveDirectAddress} is {@code int}
   * </ul>
   *
   * <p>On 64-bit devices running API 21-23, the int field cannot hold a full 64-bit address and
   * may be truncated. In that case, this method returns 0 and the caller should fall back to GC.
   */
  private static long getNativeAddress(java.nio.ByteBuffer buffer) {
    // Use cached fields — no per-call reflection lookup
    try {
      if (ReflectionCache.isOjluni && ReflectionCache.bufferAddress != null) {
        return ReflectionCache.bufferAddress.getLong(buffer);
      }
    } catch (Exception e) {
      return 0;
    }
    // luni fallback: effectiveDirectAddress is int
    try {
      if (ReflectionCache.effectiveDirectAddress != null) {
        return ReflectionCache.effectiveDirectAddress.getInt(buffer);
      }
    } catch (Exception e) {
      return 0;
    }
    return 0;
  }

  /**
   * Calls {@code android.system.Os.madvise()} via reflection.
   *
   * <p>{@code Os.madvise()} was added in API 23 but is not available at compile time when
   * building with a lower minSdk. This method uses reflection to call it safely.
   *
   * @param address The start address of the memory region.
   * @param size The size of the region in bytes.
   * @param advice The advice flag (e.g. 2 for MADV_SEQUENTIAL).
   */
  private static void madvise(long address, long size, int advice) throws Exception {
    if (ReflectionCache.madviseMethod != null) {
      ReflectionCache.madviseMethod.invoke(null, address, size, advice);
    }
  }

  @androidx.annotation.Nullable
  private static java.nio.ByteBuffer newDirectByteBuffer(long address, int size) {
    // Only supported on ojluni (API 24+) where Buffer.address is long
    if (!ReflectionCache.isOjluni || ReflectionCache.bufferAddress == null
        || ReflectionCache.bufferCapacity == null) {
      return null;
    }
    try {
      // Allocate a small DirectByteBuffer as a template (will be patched)
      java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(1);
      // Patch: replace native address with our mmap'd address
      ReflectionCache.bufferAddress.setLong(buffer, address);
      // Patch: replace capacity and limit
      ReflectionCache.bufferCapacity.setInt(buffer, size);
      buffer.limit(size);
      // Patch: clear the MemoryBlock reference so GC doesn't double-free our mmap'd memory
      if (ReflectionCache.bufferBlock != null) {
        ReflectionCache.bufferBlock.set(buffer, null);
      }
      return buffer;
    } catch (Exception e) {
      return null;
    }
  }
}
