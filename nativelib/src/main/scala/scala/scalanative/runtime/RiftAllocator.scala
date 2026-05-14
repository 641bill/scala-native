package scala.scalanative.runtime

import scala.scalanative.memory.RiftRegion
import scala.scalanative.unsafe._

object RiftAllocator {
  final val HPZone: Int = 0
  final val Scoped: Int = 1
  final val Streaming: Int = 2

  def allocate[T](region: RiftRegion, obj: T): T = intrinsic

  @extern @define("__SCALANATIVE_MEMORY_RIFT") object Impl {
    @name("scalanative_rift_init")
    def init(initialSlabs: RawSize): Unit = extern

    @name("scalanative_rift_shutdown")
    def shutdown(): Unit = extern

    @name("scalanative_rift_region_open")
    def open(kind: Int): RawPtr = extern

    @name("scalanative_rift_region_close")
    def close(region: RawPtr): Unit = extern

    @name("scalanative_rift_region_reset")
    def reset(region: RawPtr): Unit = extern

    @name("scalanative_rift_region_set_family")
    def setFamily(region: RawPtr, family: Int): Unit = extern

    @name("scalanative_rift_region_alloc_raw")
    def allocRaw(region: RawPtr, size: RawSize, align: RawSize): RawPtr =
      extern

    @name("scalanative_rift_region_alloc")
    def alloc(region: RawPtr, info: RawPtr, size: RawSize): RawPtr = extern

    @name("scalanative_rift_pool_slab_count")
    def poolSlabCount(): RawSize = extern

    @name("scalanative_rift_pool_resident_bytes")
    def poolResidentBytes(): RawSize = extern

    @name("scalanative_rift_stats_reset")
    def statsReset(): Unit = extern

    @name("scalanative_rift_stats_region_open_total")
    def statsRegionOpenTotal(): RawSize = extern

    @name("scalanative_rift_stats_region_close_total")
    def statsRegionCloseTotal(): RawSize = extern

    @name("scalanative_rift_stats_region_reset_total")
    def statsRegionResetTotal(): RawSize = extern

    @name("scalanative_rift_stats_alloc_raw_total")
    def statsAllocRawTotal(): RawSize = extern

    @name("scalanative_rift_stats_alloc_raw_bytes_total")
    def statsAllocRawBytesTotal(): RawSize = extern

    @name("scalanative_rift_stats_alloc_object_total")
    def statsAllocObjectTotal(): RawSize = extern

    @name("scalanative_rift_stats_alloc_slow_total")
    def statsAllocSlowTotal(): RawSize = extern

    @name("scalanative_rift_stats_alloc_zero_object_total")
    def statsAllocZeroObjectTotal(): RawSize = extern

    @name("scalanative_rift_stats_alloc_zero_object_bytes_total")
    def statsAllocZeroObjectBytesTotal(): RawSize = extern

    @name("scalanative_rift_stats_alloc_zero_skipped_total")
    def statsAllocZeroSkippedTotal(): RawSize = extern

    @name("scalanative_rift_stats_alloc_zero_skipped_bytes_total")
    def statsAllocZeroSkippedBytesTotal(): RawSize = extern

    @name("scalanative_rift_stats_mmap_slab_total")
    def statsMmapSlabTotal(): RawSize = extern

    @name("scalanative_rift_stats_mmap_bytes_total")
    def statsMmapBytesTotal(): RawSize = extern

    @name("scalanative_rift_stats_mmap_slab_current")
    def statsMmapSlabCurrent(): RawSize = extern

    @name("scalanative_rift_stats_mmap_slab_peak")
    def statsMmapSlabPeak(): RawSize = extern

    @name("scalanative_rift_stats_mmap_bytes_current")
    def statsMmapBytesCurrent(): RawSize = extern

    @name("scalanative_rift_stats_mmap_bytes_peak")
    def statsMmapBytesPeak(): RawSize = extern

    @name("scalanative_rift_stats_active_slab_current")
    def statsActiveSlabCurrent(): RawSize = extern

    @name("scalanative_rift_stats_active_slab_peak")
    def statsActiveSlabPeak(): RawSize = extern

    @name("scalanative_rift_stats_active_bytes_current")
    def statsActiveBytesCurrent(): RawSize = extern

    @name("scalanative_rift_stats_active_bytes_peak")
    def statsActiveBytesPeak(): RawSize = extern

    @name("scalanative_rift_stats_active_alloc_bytes_current")
    def statsActiveAllocBytesCurrent(): RawSize = extern

    @name("scalanative_rift_stats_active_alloc_bytes_peak")
    def statsActiveAllocBytesPeak(): RawSize = extern

    @name("scalanative_rift_stats_family_alloc_raw_bytes_total")
    def statsFamilyAllocRawBytesTotal(family: Int): RawSize = extern

    @name("scalanative_rift_stats_family_active_bytes_current")
    def statsFamilyActiveBytesCurrent(family: Int): RawSize = extern

    @name("scalanative_rift_stats_family_active_bytes_peak")
    def statsFamilyActiveBytesPeak(family: Int): RawSize = extern

    @name("scalanative_rift_stats_family_active_alloc_bytes_current")
    def statsFamilyActiveAllocBytesCurrent(family: Int): RawSize = extern

    @name("scalanative_rift_stats_family_active_alloc_bytes_peak")
    def statsFamilyActiveAllocBytesPeak(family: Int): RawSize = extern

    @name("scalanative_rift_stats_tls_reuse_total")
    def statsTlsReuseTotal(): RawSize = extern

    @name("scalanative_rift_stats_pool_reuse_total")
    def statsPoolReuseTotal(): RawSize = extern

    @name("scalanative_rift_stats_region_op_ns")
    def statsRegionOpNanos(): RawSize = extern

    @name("scalanative_rift_stats_open_ns")
    def statsOpenNanos(): RawSize = extern

    @name("scalanative_rift_stats_close_ns")
    def statsCloseNanos(): RawSize = extern

    @name("scalanative_rift_stats_reset_ns")
    def statsResetNanos(): RawSize = extern

    @name("scalanative_rift_stats_slow_alloc_ns")
    def statsSlowAllocNanos(): RawSize = extern
  }
}
