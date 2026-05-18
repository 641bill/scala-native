package rift.portable

import scala.collection.mutable.ArrayBuffer

final class HeapFallbackBackend extends RiftBackend:
  val kind: BackendKind = BackendKind.HeapFallback
  val stats: RiftStats = new RiftStats

final class AnalysisOnlyBackend extends RiftBackend:
  val kind: BackendKind = BackendKind.AnalysisOnly
  val stats: RiftStats = new RiftStats

final class PoolingBackend(val kind: BackendKind) extends RiftBackend:
  val stats: RiftStats = new RiftStats

  override private[portable] def borrowReusable[A <: ReusableRecord](
      scope: RiftScope,
      pool: ObjectPool[A]
  )(init: A => Unit): A =
    scope.checkOpen()
    val value = pool.borrow(stats)
    var registered = false
    try
      init(value)
      scope.registerBorrowed(pool, value)
      registered = true
      value
    finally
      if !registered then pool.release(value)

final class WasmArenaBackend(initialBytes: Int) extends RiftBackend:
  val kind: BackendKind = BackendKind.WasmLinearMemory
  val stats: RiftStats = new RiftStats
  private var memory = new Array[Byte](initialBytes)
  private var cursor = 0
  private val marks = ArrayBuffer.empty[Int]

  override private[portable] def openEpoch(scope: RiftScope): Unit =
    super.openEpoch(scope)
    marks += cursor

  override private[portable] def closeEpoch(scope: RiftScope): Unit =
    scope.closeScope()
    scope.releaseBorrowed()
    if marks.nonEmpty then cursor = marks.remove(marks.length - 1)
    else cursor = 0
    stats.closes += 1L

  override private[portable] def allocBytes(
      scope: RiftScope,
      size: Int,
      align: Int
  ): WasmSlice =
    scope.checkOpen()
    if size < 0 then throw new IllegalArgumentException("size must be non-negative")
    if align <= 0 then throw new IllegalArgumentException("align must be positive")
    val aligned = alignUp(cursor, align)
    val end = aligned + size
    if end > memory.length then
      var next = math.max(memory.length, 1)
      while end > next do next *= 2
      memory = java.util.Arrays.copyOf(memory, next)
    cursor = end
    stats.arenaAllocations += 1L
    stats.arenaBytes += size.toLong
    stats.arenaHighWaterBytes = math.max(stats.arenaHighWaterBytes, cursor.toLong)
    WasmSlice(aligned, size)

  private def alignUp(value: Int, align: Int): Int =
    val mask = align - 1
    if (align & mask) == 0 then (value + mask) & ~mask
    else
      var out = value
      while out % align != 0 do out += 1
      out

final class NativeModelBackend extends RiftBackend:
  val kind: BackendKind = BackendKind.ScalaNativeModel
  val stats: RiftStats = new RiftStats

  override private[portable] def allocHeap[A <: AnyRef](scope: RiftScope)(value: => A): A =
    scope.checkOpen()
    val out = value
    stats.regionModelAllocs += 1L
    out

  override private[portable] def borrowReusable[A <: ReusableRecord](
      scope: RiftScope,
      pool: ObjectPool[A]
  )(init: A => Unit): A =
    scope.checkOpen()
    val value = pool.newObject()
    init(value)
    stats.regionModelAllocs += 1L
    value
