import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.memory.SafeZone._
import scala.scalanative.runtime.SafeZoneAllocator.allocate

object GCBenchRuntimeMatrixHelpers {
  private final class HeapNode(
      var left: HeapNode,
      var right: HeapNode,
      var i: Int,
      var j: Int
  )

  private def treeSize(depth: Int): Int =
    (1 << (depth + 1)) - 1

  private def numIters(depth: Int): Int =
    2 * treeSize(GCBenchConfig.stretchTreeDepth) / treeSize(depth)

  private def populateHeap(depth: Int, node: HeapNode): Unit =
    if (depth > 0) {
      node.left = new HeapNode(null, null, 0, 0)
      node.right = new HeapNode(null, null, 0, 0)
      populateHeap(depth - 1, node.left)
      populateHeap(depth - 1, node.right)
    }

  private def makeHeapTree(depth: Int): HeapNode =
    if (depth <= 0) new HeapNode(null, null, 0, 0)
    else new HeapNode(makeHeapTree(depth - 1), makeHeapTree(depth - 1), 0, 0)

  def runHeap(): Boolean = {
    val cfg = GCBenchConfig

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

    def populate(depth: Int, node: HeapNode): Unit =
      if (depth > 0) {
        node.left = new HeapNode(null, null, 0, 0)
        node.right = new HeapNode(null, null, 0, 0)
        populate(depth - 1, node.left)
        populate(depth - 1, node.right)
      }

    def makeTree(depth: Int): HeapNode =
      if (depth <= 0) new HeapNode(null, null, 0, 0)
      else new HeapNode(makeTree(depth - 1), makeTree(depth - 1), 0, 0)

    def construction(depth: Int): Unit = {
      val iterations = numIters(depth)
      var iter = 0
      while (iter < iterations) {
        tempTree = new HeapNode(null, null, 0, 0)
        populate(depth, tempTree)
        tempTree = null
        iter += 1
      }

      iter = 0
      while (iter < iterations) {
        tempTree = makeTree(depth)
        tempTree = null
        iter += 1
      }
    }

    i = cfg.minTreeDepth
    while (i <= cfg.maxTreeDepth) {
      construction(i)
      i += cfg.treeDepthStep
    }

    longLivedTree != null && array(1000) == 1.0 / 1000
  }

  def runSafeZone(): Boolean = {
    val cfg = GCBenchConfig

    def runTopDownBatch(depth: Int, batch: Int)(using sz: SafeZone^): Unit = {
      class Node(var left: Node^{sz}, var right: Node^{sz}, var i: Int, var j: Int)

      def populate(currentDepth: Int, node: Node^{sz}): Unit =
        if (currentDepth > 0) {
          node.left = allocate(sz, new Node(null, null, 0, 0))
          node.right = allocate(sz, new Node(null, null, 0, 0))
          populate(currentDepth - 1, node.left)
          populate(currentDepth - 1, node.right)
        }

      var iter = 0
      while (iter < batch) {
        var tempTree: Node^{sz} = allocate(sz, new Node(null, null, 0, 0))
        populate(depth, tempTree)
        tempTree = null
        iter += 1
      }
    }

    def runBottomUpBatch(depth: Int, batch: Int)(using sz: SafeZone^): Unit = {
      class Node(var left: Node^{sz}, var right: Node^{sz}, var i: Int, var j: Int)

      def makeTree(currentDepth: Int): Node^{sz} =
        if (currentDepth <= 0) {
          allocate(sz, new Node(null, null, 0, 0))
        } else {
          allocate(
            sz,
            new Node(makeTree(currentDepth - 1), makeTree(currentDepth - 1), 0, 0)
          )
        }

      var iter = 0
      while (iter < batch) {
        var tempTree: Node^{sz} = makeTree(depth)
        tempTree = null
        iter += 1
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
      val iterations = numIters(i)
      SafeZone { sz ?=>
        var remaining = iterations
        while (remaining > 0) {
          val batch = if (remaining < cfg.safeZoneBatchSize) remaining
          else cfg.safeZoneBatchSize
          runTopDownBatch(i, batch)(using sz)
          remaining -= batch
        }

        remaining = iterations
        while (remaining > 0) {
          val batch = if (remaining < cfg.safeZoneBatchSize) remaining
          else cfg.safeZoneBatchSize
          runBottomUpBatch(i, batch)(using sz)
          remaining -= batch
        }
      }
      i += cfg.treeDepthStep
    }

    longLivedTree != null && array(1000) == 1.0 / 1000
  }

  def runRift(kind: Int): Boolean = {
    val cfg = GCBenchConfig

    def runTopDownBatch(region: RiftRegion, depth: Int, batch: Int): Unit = {
      class Node(var left: Node, var right: Node, var i: Int, var j: Int)

      def populate(currentDepth: Int, node: Node): Unit =
        if (currentDepth > 0) {
          node.left = region.alloc(new Node(null, null, 0, 0))
          node.right = region.alloc(new Node(null, null, 0, 0))
          populate(currentDepth - 1, node.left)
          populate(currentDepth - 1, node.right)
        }

      var iter = 0
      while (iter < batch) {
        var tempTree: Node = region.alloc(new Node(null, null, 0, 0))
        populate(depth, tempTree)
        tempTree = null
        iter += 1
      }
    }

    def runBottomUpBatch(region: RiftRegion, depth: Int, batch: Int): Unit = {
      class Node(var left: Node, var right: Node, var i: Int, var j: Int)

      def makeTree(currentDepth: Int): Node =
        if (currentDepth <= 0) {
          region.alloc(new Node(null, null, 0, 0))
        } else {
          region.alloc(
            new Node(makeTree(currentDepth - 1), makeTree(currentDepth - 1), 0, 0)
          )
        }

      var iter = 0
      while (iter < batch) {
        var tempTree: Node = makeTree(depth)
        tempTree = null
        iter += 1
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
      val iterations = numIters(i)
      val region = RiftRegion.open(kind)
      try {
        var remaining = iterations
        while (remaining > 0) {
          val batch = if (remaining < cfg.safeZoneBatchSize) remaining
          else cfg.safeZoneBatchSize
          runTopDownBatch(region, i, batch)
          remaining -= batch
        }

        remaining = iterations
        while (remaining > 0) {
          val batch = if (remaining < cfg.safeZoneBatchSize) remaining
          else cfg.safeZoneBatchSize
          runBottomUpBatch(region, i, batch)
          remaining -= batch
        }
      } finally region.close()
      i += cfg.treeDepthStep
    }

    longLivedTree != null && array(1000) == 1.0 / 1000
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

@main def GCBenchRuntimeMatrix(mode: String = "heap"): Unit = {
  GCBenchRuntimeMatrixHelpers.printConfig(mode)
  mode match {
    case "heap" =>
      BenchmarkRunner.runBenchmark("gcbench-heap", GCBenchConfig.benchmarkRuns) {
        GCBenchRuntimeMatrixHelpers.runHeap()
      }
    case "safezone" =>
      BenchmarkRunner.runBenchmark(
        "gcbench-safezone",
        GCBenchConfig.benchmarkRuns
      ) {
        GCBenchRuntimeMatrixHelpers.runSafeZone()
      }
    case "rift-hp" =>
      RiftRegion.init(0)
      try {
        BenchmarkRunner.runBenchmark("gcbench-rift-hp", GCBenchConfig.benchmarkRuns) {
          GCBenchRuntimeMatrixHelpers.runRift(RiftRegion.HPZone)
        }
      } finally RiftRegion.shutdown()
    case other =>
      throw new IllegalArgumentException(
        s"unknown gcbench mode: $other (expected heap, safezone, or rift-hp)"
      )
  }
}
