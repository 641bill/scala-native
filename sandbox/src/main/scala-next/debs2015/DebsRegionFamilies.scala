package debs2015

import scala.language.experimental.captureChecking

import scala.scalanative.memory.RiftRegion

private[debs2015] object DebsRegionFamilies {
  final val Input = 1
  final val Snapshot = 2
  final val CheckedParent = 3
  final val Q1Window = 4
  final val Q2ProfitWindow = 5
  final val Q2EmptyWindow = 6
  final val Count = 7

  private val Enabled =
    java.lang.System.getenv("DEBS2015_RIFT_FAMILY_STATS") == "1"

  def enabled: Boolean = Enabled

  def set(region: RiftRegion^, family: Int): Unit =
    if (Enabled && region != null)
      RiftRegion.setDiagnosticFamily(region, family)

  def setChildBucket(
      parent: RiftRegion.StreamingRegion^,
      bucket: RiftRegion.ChildBucket^{parent},
      family: Int
  ): Unit =
    if (Enabled)
      set(RiftRegion.childBucketRegion(parent, bucket), family)

  def emptyLongs(): Array[Long] =
    new Array[Long](Count)
}
