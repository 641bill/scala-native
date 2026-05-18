package rift.portable

final class PairRecord(var key: Int, var value: Long) extends ReusableRecord:
  def set(nextKey: Int, nextValue: Long): Unit =
    key = nextKey
    value = nextValue

  def clearForReuse(): Unit =
    key = 0
    value = 0L

final class DataflowEvent(
    var timestamp: Int,
    var key: Int,
    var left: Long,
    var right: Long
) extends ReusableRecord:
  def set(nextTimestamp: Int, nextKey: Int, nextLeft: Long, nextRight: Long): Unit =
    timestamp = nextTimestamp
    key = nextKey
    left = nextLeft
    right = nextRight

  def clearForReuse(): Unit =
    timestamp = 0
    key = 0
    left = 0L
    right = 0L
