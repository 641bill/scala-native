package rift.portable

import scala.collection.mutable.ArrayBuffer

object PortableRiftWorkloads:
  final case class WorkloadResult(checksum: Long, output: Long)

  def retainedEpoch(
      runtime: RiftRuntime,
      records: Int,
      epochSize: Int,
      usePool: Boolean
  ): WorkloadResult =
    val pool = new ObjectPool(() => new PairRecord(0, 0L))
    var checksum = 0L
    var output = 0L
    var processed = 0
    while processed < records do
      val limit = math.min(epochSize, records - processed)
      val retained = new ArrayBuffer[PairRecord](limit)
      runtime.epoch { scope =>
        var i = 0
        while i < limit do
          val key = (processed + i) & 1023
          val value = ((processed + i).toLong * 1103515245L + 12345L) & 0xffffL
          val record =
            if usePool then scope.borrow(pool)(_.set(key, value))
            else scope.alloc(new PairRecord(key, value))
          retained += record
          checksum += record.key.toLong * 31L + record.value
          output += 1L
          i += 1
        retained.clear()
      }
      processed += limit
    WorkloadResult(checksum, output)

  def broomAggregate(
      runtime: RiftRuntime,
      records: Int,
      epochSize: Int,
      activeTimestamps: Int,
      usePool: Boolean
  ): WorkloadResult =
    val pool = new ObjectPool(() => new DataflowEvent(0, 0, 0L, 0L))
    val sums = Array.fill(activeTimestamps, 256)(0L)
    var checksum = 0L
    var output = 0L
    var processed = 0
    while processed < records do
      val limit = math.min(epochSize, records - processed)
      runtime.epoch { scope =>
        var i = 0
        while i < limit do
          val global = processed + i
          val timestamp = (global / epochSize) % activeTimestamps
          val key = global & 255
          val left = ((global.toLong * 1664525L) + 1013904223L) & 0xffffL
          val right = ((global.toLong * 22695477L) + 1L) & 0xffffL
          val event =
            if usePool then scope.borrow(pool)(_.set(timestamp, key, left, right))
            else scope.alloc(new DataflowEvent(timestamp, key, left, right))
          sums(timestamp)(key) += event.left + event.right
          checksum += event.timestamp.toLong * 17L + event.key.toLong * 31L + event.left - event.right
          output += 1L
          i += 1
        val closingTimestamp = ((processed / epochSize) + activeTimestamps - 1) % activeTimestamps
        var k = 0
        while k < sums(closingTimestamp).length do
          checksum ^= sums(closingTimestamp)(k) + k.toLong
          sums(closingTimestamp)(k) = 0L
          k += 1
      }
      processed += limit
    WorkloadResult(checksum, output)
