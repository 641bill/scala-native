import scala.language.experimental.captureChecking

import scala.scalanative.memory.RiftRegion

final class RiftBox(val value: Int)

@main def RiftSmoke(): Unit = {
  RiftRegion.init(8)

  val region = RiftRegion.open(RiftRegion.HPZone)
  val ptr = region.alloc(64, 16)
  if (ptr == null)
    throw new IllegalStateException("rift alloc failed")

  val box = region.alloc(new RiftBox(42))
  if (box.value != 42)
    throw new IllegalStateException("rift object alloc failed")

  val arr = region.alloc(new Array[Int](4))
  arr(0) = 7
  if (arr(0) != 7)
    throw new IllegalStateException("rift array alloc failed")

  region.close()
  RiftRegion.shutdown()
  println("Rift smoke ok")
}
