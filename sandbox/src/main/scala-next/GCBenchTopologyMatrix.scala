import scala.language.experimental.captureChecking

import scala.scalanative.memory.SafeZone
import scala.scalanative.memory.SafeZone._
import scala.scalanative.runtime.SafeZoneAllocator.allocate

object GCBenchTopologyMatrixHelpers {
  def runTopologyA(): Boolean = {
    class HeapNode(var left: HeapNode, var right: HeapNode, var i: Int, var j: Int)
    val cfg = GCBenchConfig
    val batchSize = cfg.safeZoneBatchSize

    def treeSize(depth: Int): Int =
      (1 << (depth + 1)) - 1

    def numIters(depth: Int): Int =
      2 * treeSize(cfg.stretchTreeDepth) / treeSize(depth)

    def populateHeap(depth: Int, node: HeapNode): Unit =
      if (depth > 0) {
        node.left = new HeapNode(null, null, 0, 0)
        node.right = new HeapNode(null, null, 0, 0)
        populateHeap(depth - 1, node.left)
        populateHeap(depth - 1, node.right)
      }

    def makeHeapTree(depth: Int): HeapNode =
      if (depth <= 0) new HeapNode(null, null, 0, 0)
      else new HeapNode(makeHeapTree(depth - 1), makeHeapTree(depth - 1), 0, 0)

    def runTopDownBatch(depth: Int, batch: Int): Unit =
      SafeZone { sz ?=>
        class TempNode(
            var left: TempNode^{sz},
            var right: TempNode^{sz},
            var i: Int,
            var j: Int
        )

        def populate(currentDepth: Int, node: TempNode^{sz}): Unit =
          if (currentDepth > 0) {
            node.left = allocate(sz, new TempNode(null, null, 0, 0))
            node.right = allocate(sz, new TempNode(null, null, 0, 0))
            populate(currentDepth - 1, node.left)
            populate(currentDepth - 1, node.right)
          }

        var iter = 0
        while (iter < batch) {
          var tempTree: TempNode^{sz} = allocate(sz, new TempNode(null, null, 0, 0))
          populate(depth, tempTree)
          tempTree = null
          iter += 1
        }
      }

    def runBottomUpBatch(depth: Int, batch: Int): Unit =
      SafeZone { sz ?=>
        class TempNode(
            var left: TempNode^{sz},
            var right: TempNode^{sz},
            var i: Int,
            var j: Int
        )

        def makeTree(currentDepth: Int): TempNode^{sz} =
          if (currentDepth <= 0) {
            allocate(sz, new TempNode(null, null, 0, 0))
          } else {
            allocate(
              sz,
              new TempNode(
                makeTree(currentDepth - 1),
                makeTree(currentDepth - 1),
                0,
                0
              )
            )
          }

        var iter = 0
        while (iter < batch) {
          var tempTree: TempNode^{sz} = makeTree(depth)
          tempTree = null
          iter += 1
        }
      }

    def construction(depth: Int): Unit = {
      val iterations = numIters(depth)

      var remaining = iterations
      while (remaining > 0) {
        val currentBatch = if (remaining < batchSize) remaining else batchSize
        runTopDownBatch(depth, currentBatch)
        remaining -= currentBatch
      }

      remaining = iterations
      while (remaining > 0) {
        val currentBatch = if (remaining < batchSize) remaining else batchSize
        runBottomUpBatch(depth, currentBatch)
        remaining -= currentBatch
      }
    }

    var tempTree: HeapNode = makeHeapTree(cfg.stretchTreeDepth)
    tempTree = null

    val longLivedTree = new HeapNode(null, null, 0, 0)
    populateHeap(cfg.longLivedTreeDepth, longLivedTree)

    val array = new Array[Double](cfg.arraySize)
    var i = 0
    while (i < cfg.arraySize / 2) {
      array(i) = 1.0 / i
      i += 1
    }

    i = cfg.minTreeDepth
    while (i <= cfg.maxTreeDepth) {
      construction(i)
      i += cfg.treeDepthStep
    }

    longLivedTree != null && array(1000) == 1.0 / 1000
  }

  def runTopologyB(): Boolean = {
    val cfg = GCBenchConfig
    val batchSize = cfg.safeZoneBatchSize

    def treeSize(depth: Int): Int =
      (1 << (depth + 1)) - 1

    def numIters(depth: Int): Int =
      2 * treeSize(cfg.stretchTreeDepth) / treeSize(depth)

    val array = new Array[Double](cfg.arraySize)
    var i = 0
    while (i < cfg.arraySize / 2) {
      array(i) = 1.0 / i
      i += 1
    }

    SafeZone { outer ?=>
      class LongNode(
          var left: LongNode^{outer},
          var right: LongNode^{outer},
          var i: Int,
          var j: Int
      )

      def populateLong(depth: Int, node: LongNode^{outer}): Unit =
        if (depth > 0) {
          node.left = allocate(outer, new LongNode(null, null, 0, 0))
          node.right = allocate(outer, new LongNode(null, null, 0, 0))
          populateLong(depth - 1, node.left)
          populateLong(depth - 1, node.right)
        }

      def stretchTree(): Unit =
        SafeZone { inner ?=>
          class TempNode(
              var left: TempNode^{inner},
              var right: TempNode^{inner},
              var i: Int,
              var j: Int
          )

          def makeTree(depth: Int): TempNode^{inner} =
            if (depth <= 0) {
              allocate(inner, new TempNode(null, null, 0, 0))
            } else {
              allocate(
                inner,
                new TempNode(makeTree(depth - 1), makeTree(depth - 1), 0, 0)
              )
            }

          var tempTree: TempNode^{inner} = makeTree(cfg.stretchTreeDepth)
          tempTree = null
        }

      def runTopDownBatch(depth: Int, batch: Int): Unit =
        SafeZone { inner ?=>
          class TempNode(
              var left: TempNode^{inner},
              var right: TempNode^{inner},
              var i: Int,
              var j: Int
          )

          def populate(currentDepth: Int, node: TempNode^{inner}): Unit =
            if (currentDepth > 0) {
              node.left = allocate(inner, new TempNode(null, null, 0, 0))
              node.right = allocate(inner, new TempNode(null, null, 0, 0))
              populate(currentDepth - 1, node.left)
              populate(currentDepth - 1, node.right)
            }

          var iter = 0
          while (iter < batch) {
            var tempTree: TempNode^{inner} =
              allocate(inner, new TempNode(null, null, 0, 0))
            populate(depth, tempTree)
            tempTree = null
            iter += 1
          }
        }

      def runBottomUpBatch(depth: Int, batch: Int): Unit =
        SafeZone { inner ?=>
          class TempNode(
              var left: TempNode^{inner},
              var right: TempNode^{inner},
              var i: Int,
              var j: Int
          )

          def makeTree(currentDepth: Int): TempNode^{inner} =
            if (currentDepth <= 0) {
              allocate(inner, new TempNode(null, null, 0, 0))
            } else {
              allocate(
                inner,
                new TempNode(
                  makeTree(currentDepth - 1),
                  makeTree(currentDepth - 1),
                  0,
                  0
                )
              )
            }

          var iter = 0
          while (iter < batch) {
            var tempTree: TempNode^{inner} = makeTree(depth)
            tempTree = null
            iter += 1
          }
        }

      def construction(depth: Int): Unit = {
        val iterations = numIters(depth)

        var remaining = iterations
        while (remaining > 0) {
          val currentBatch = if (remaining < batchSize) remaining else batchSize
          runTopDownBatch(depth, currentBatch)
          remaining -= currentBatch
        }

        remaining = iterations
        while (remaining > 0) {
          val currentBatch = if (remaining < batchSize) remaining else batchSize
          runBottomUpBatch(depth, currentBatch)
          remaining -= currentBatch
        }
      }

      stretchTree()

      val longLivedTree: LongNode^{outer} =
        allocate(outer, new LongNode(null, null, 0, 0))
      populateLong(cfg.longLivedTreeDepth, longLivedTree)

      i = cfg.minTreeDepth
      while (i <= cfg.maxTreeDepth) {
        construction(i)
        i += cfg.treeDepthStep
      }

      longLivedTree != null && array(1000) == 1.0 / 1000
    }
  }

  def printConfig(mode: String): Unit = {
    val cfg = GCBenchConfig
    val rootsMode = sys.env.getOrElse("SAFEZONE_ROOTS_MODE", "0")
    val pageSize = sys.env.getOrElse("SAFEZONE_PAGE_SIZE", "default")
    println(
      s"CONFIG mode=$mode runs=${cfg.benchmarkRuns} stretch_depth=${cfg.stretchTreeDepth} long_lived_depth=${cfg.longLivedTreeDepth} array_size=${cfg.arraySize} min_depth=${cfg.minTreeDepth} max_depth=${cfg.maxTreeDepth} depth_step=${cfg.treeDepthStep} batch_size=${cfg.safeZoneBatchSize} safezone_roots_mode=$rootsMode safezone_page_size=$pageSize"
    )
  }
}

@main def GCBenchTopologyMatrix(mode: String = "heap"): Unit = {
  GCBenchTopologyMatrixHelpers.printConfig(mode)

  val stats = mode match {
    case "heap" =>
      BenchmarkRunner.runBenchmark(
        name = "gcbench-heap",
        numRuns = GCBenchConfig.benchmarkRuns
      )(GCBenchRuntimeMatrixHelpers.runHeap())
    case "topology-a" =>
      BenchmarkRunner.runBenchmark(
        name = "gcbench-topology-a",
        numRuns = GCBenchConfig.benchmarkRuns
      )(GCBenchTopologyMatrixHelpers.runTopologyA())
    case "topology-b" =>
      BenchmarkRunner.runBenchmark(
        name = "gcbench-topology-b",
        numRuns = GCBenchConfig.benchmarkRuns
      )(GCBenchTopologyMatrixHelpers.runTopologyB())
    case other =>
      throw new IllegalArgumentException(
        s"unknown mode '$other'; expected heap, topology-a, or topology-b"
      )
  }

  if (stats.medianMs.isNaN)
    throw new IllegalStateException("benchmark stats were not computed")
}
