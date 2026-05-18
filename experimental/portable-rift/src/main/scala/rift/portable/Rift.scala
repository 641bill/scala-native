//> using scala "3.7.3"

package rift.portable

import scala.collection.mutable.ArrayBuffer

enum BackendKind(val label: String):
  case HeapFallback extends BackendKind("heap-gc")
  case AnalysisOnly extends BackendKind("analysis-only")
  case JvmPoolArena extends BackendKind("jvm-pool-arena")
  case ScalaJsPool extends BackendKind("scala-js-pool")
  case WasmLinearMemory extends BackendKind("wasm-linear-memory")
  case ScalaNativeModel extends BackendKind("scala-native-real-regions")

final class RiftStats:
  var opens: Long = 0L
  var closes: Long = 0L
  var heapFallbackAllocs: Long = 0L
  var regionModelAllocs: Long = 0L
  var pooledFreshAllocs: Long = 0L
  var pooledReuses: Long = 0L
  var arenaAllocations: Long = 0L
  var arenaBytes: Long = 0L
  var arenaHighWaterBytes: Long = 0L
  var heapRootRefs: Long = 0L
  var staticMetadataRefs: Long = 0L
  var dynamicHeapRefs: Long = 0L

  def snapshot: String =
    s"opens=$opens closes=$closes heapFallbackAllocs=$heapFallbackAllocs " +
      s"regionModelAllocs=$regionModelAllocs pooledFreshAllocs=$pooledFreshAllocs " +
      s"pooledReuses=$pooledReuses arenaAllocations=$arenaAllocations " +
      s"arenaBytes=$arenaBytes arenaHighWaterBytes=$arenaHighWaterBytes " +
      s"heapRootRefs=$heapRootRefs staticMetadataRefs=$staticMetadataRefs " +
      s"dynamicHeapRefs=$dynamicHeapRefs"

trait ReusableRecord:
  def clearForReuse(): Unit

final class ObjectPool[A <: ReusableRecord](fresh: () => A):
  private val free = ArrayBuffer.empty[A]

  private[portable] def newObject(): A =
    fresh()

  private[portable] def borrow(stats: RiftStats): A =
    if free.nonEmpty then
      stats.pooledReuses += 1L
      free.remove(free.length - 1)
    else
      stats.pooledFreshAllocs += 1L
      fresh()

  private[portable] def release(value: A): Unit =
    value.clearForReuse()
    free += value

  def cached: Int = free.length

final case class HeapRoot[+A](value: A)
final case class StaticMetadata[+A <: AnyRef](value: A)
final case class WasmSlice(offset: Int, size: Int)

final class RiftScope private[portable] (
    private[portable] val runtime: RiftRuntime,
    val backendKind: BackendKind
):
  private var open = true
  private val borrowed = ArrayBuffer.empty[(ObjectPool[ReusableRecord], ReusableRecord)]

  def isOpen: Boolean = open
  def isClosed: Boolean = !open

  def alloc[A <: AnyRef](value: => A): A =
    runtime.backend.allocHeap(this)(value)

  def borrow[A <: ReusableRecord](pool: ObjectPool[A])(init: A => Unit): A =
    runtime.backend.borrowReusable(this, pool)(init)

  def allocBytes(size: Int, align: Int = 8): WasmSlice =
    runtime.backend.allocBytes(this, size, align)

  def epoch[A](body: RiftScope => A): A =
    checkOpen()
    runtime.epoch(body)

  def acceptRoot[A](root: HeapRoot[A]): A =
    checkOpen()
    runtime.backend.stats.heapRootRefs += 1L
    root.value

  def acceptStatic[A <: AnyRef](metadata: StaticMetadata[A]): A =
    checkOpen()
    runtime.backend.stats.staticMetadataRefs += 1L
    metadata.value

  def acceptDynamicHeap[A <: AnyRef](value: A): A =
    checkOpen()
    runtime.acceptDynamicHeap(value)

  private[portable] def checkOpen(): Unit =
    if !open then
      throw new IllegalStateException(
        s"Rift ${backendKind.label} scope is already closed"
      )

  private[portable] def closeScope(): Unit =
    open = false

  private[portable] def registerBorrowed[A <: ReusableRecord](
      pool: ObjectPool[A],
      value: A
  ): Unit =
    borrowed += ((pool.asInstanceOf[ObjectPool[ReusableRecord]], value))

  private[portable] def releaseBorrowed(): Unit =
    var i = borrowed.length - 1
    while i >= 0 do
      val (pool, value) = borrowed(i)
      pool.release(value)
      i -= 1
    borrowed.clear()

trait RiftBackend:
  def kind: BackendKind
  def stats: RiftStats

  private[portable] def openEpoch(scope: RiftScope): Unit =
    stats.opens += 1L

  private[portable] def closeEpoch(scope: RiftScope): Unit =
    scope.closeScope()
    scope.releaseBorrowed()
    stats.closes += 1L

  private[portable] def allocHeap[A <: AnyRef](scope: RiftScope)(value: => A): A =
    scope.checkOpen()
    val out = value
    stats.heapFallbackAllocs += 1L
    out

  private[portable] def borrowReusable[A <: ReusableRecord](
      scope: RiftScope,
      pool: ObjectPool[A]
  )(init: A => Unit): A =
    scope.checkOpen()
    val value = pool.newObject()
    init(value)
    stats.heapFallbackAllocs += 1L
    value

  private[portable] def allocBytes(
      scope: RiftScope,
      size: Int,
      align: Int
  ): WasmSlice =
    scope.checkOpen()
    if size < 0 then throw new IllegalArgumentException("size must be non-negative")
    if align <= 0 then throw new IllegalArgumentException("align must be positive")
    stats.heapFallbackAllocs += 1L
    WasmSlice(0, size)

final class RiftRuntime private[portable] (
    private[portable] val backend: RiftBackend,
    val rootFreeEligible: Boolean = false
):
  def kind: BackendKind = backend.kind
  def stats: RiftStats = backend.stats

  def epoch[A](body: RiftScope => A): A =
    val scope = new RiftScope(this, backend.kind)
    backend.openEpoch(scope)
    try body(scope)
    finally backend.closeEpoch(scope)

  private[portable] def acceptDynamicHeap[A <: AnyRef](value: A): A =
    if rootFreeEligible then
      throw new IllegalArgumentException(
        "root-free portable Rift path cannot accept unwrapped dynamic heap metadata; use HeapRoot or StaticMetadata"
      )
    backend.stats.dynamicHeapRefs += 1L
    value

object Rift:
  def heapFallback(): RiftRuntime =
    new RiftRuntime(new HeapFallbackBackend)

  def analysisOnly(): RiftRuntime =
    new RiftRuntime(new AnalysisOnlyBackend)

  def jvmPoolArena(): RiftRuntime =
    new RiftRuntime(new PoolingBackend(BackendKind.JvmPoolArena))

  def rootFreeJvmPoolArena(): RiftRuntime =
    new RiftRuntime(new PoolingBackend(BackendKind.JvmPoolArena), rootFreeEligible = true)

  def scalaJsPool(): RiftRuntime =
    new RiftRuntime(new PoolingBackend(BackendKind.ScalaJsPool))

  def wasmLinearMemory(initialBytes: Int = 64 * 1024): RiftRuntime =
    new RiftRuntime(new WasmArenaBackend(initialBytes))

  def scalaNativeModel(): RiftRuntime =
    new RiftRuntime(new NativeModelBackend)

  def root[A](value: A): HeapRoot[A] =
    HeapRoot(value)

  def staticMetadata[A <: AnyRef](value: A): StaticMetadata[A] =
    StaticMetadata(value)
