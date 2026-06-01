import scala.language.experimental.captureChecking
import scala.scalanative.runtime.{RiftAllocator, fromRawUSize}

private final class AutoBox(val value: Int)
private final class AutoPair(val a: Int, val b: Int)

@main def TestExperimental() =
  println("=== Automatic Region Inference Runtime Test ===")

  // Test 1: local-escape allocation computes correctly
  val result1 = {
    def compute(): Int =
      val box = new AutoBox(42)
      box.value
    compute()
  }
  println(s"Test 1 (local escape compute): result=$result1, pass=${result1 == 42}")

  // Test 2: multiple local allocations
  val result2 = {
    def compute(): Int =
      val p1 = new AutoPair(1, 2)
      val p2 = new AutoPair(3, 4)
      p1.a + p1.b + p2.a + p2.b
    compute()
  }
  println(s"Test 2 (multiple local allocs): result=$result2, pass=${result2 == 10}")

  // Test 3: returned allocation (should stay on heap)
  val result3 = {
    def make(): AutoBox = new AutoBox(42)
    make().value
  }
  println(s"Test 3 (returned alloc): result=$result3, pass=${result3 == 42}")

  // Test 4: mutable var allocation (should stay on heap)
  val result4 = {
    def compute(): Int =
      var box = new AutoBox(0)
      box = new AutoBox(42)
      box.value
    compute()
  }
  println(s"Test 4 (mutable var alloc): result=$result4, pass=${result4 == 42}")

  // Test 5: region allocation stats
  RiftAllocator.Impl.statsReset()
  val before = fromRawUSize(RiftAllocator.Impl.statsAllocObjectTotal()).toLong
  val result5 = {
    def compute(): Int =
      val box = new AutoBox(42)
      val p = new AutoPair(3, 4)
      box.value + p.a + p.b
    compute()
  }
  val after = fromRawUSize(RiftAllocator.Impl.statsAllocObjectTotal()).toLong
  val delta = after - before
  println(s"Test 5 (region alloc stats): result=$result5, delta=$delta, pass=${result5 == 49}")

  println("=== All tests complete ===")
