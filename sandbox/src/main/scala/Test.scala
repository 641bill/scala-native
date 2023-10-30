import java.lang.Thread
import scala.collection.mutable.ArrayBuffer

object Test {
  def main(args: Array[String]): Unit = {
    for (i <- 0 to 1000000) {
      val ab = ArrayBuffer(i, i + 1, i + 2)
    }
    // Initialize a thread
    val thread = new Thread {
      override def run(): Unit = {
        println("Hello, World! from thread")
      }
    }
    thread.start()
    // // Initializa a bunch of threads
    val threads = (1 to 100).map { i =>
      new Thread {
        override def run(): Unit = {
          println("Hello, World! from thread " + i)
        }
      }
    }
    threads.foreach(_.start())
    thread.join()
    println("Single thread joined")
    threads.foreach(_.join())
    println("All threads joined")
    println("Hello, World!")
    // Do some tests that allocates a large chunk of memory
    // Allocate a large array
    val array = new Array[Int](100000)
    println(array)
    println(array.length)

    class Foo(val x: Int)
    val array2 = new Array[Foo](100000)
    import scala.scalanative.runtime.Intrinsics._ 
    import scala.scalanative.runtime.fromRawPtr 
    println(fromRawPtr(castObjectToRawPtr(array2)))
    // println(array2)
    println(array2.length)
    
    println("Running GCBench")
    val benchmarkResult = gcbench.GCBenchBenchmark.run("")
    println(s"GCBench result: $benchmarkResult")

    // val iterations = 10
    // for (iter <- 0 until iterations) {
    //   val threadsCount = Runtime.getRuntime().availableProcessors() * 4
    //   val ids = new scala.Array[String](threadsCount)
    //   val threads = Seq.tabulate(threadsCount) { id =>
    //     new Thread(() => {
    //       val _ = generateGarbage() // can be GCed
    //       ids(id) = Thread.currentThread().getName() // should be kept after thread finishes
    //       Thread.sleep(10)
    //     })
    //   }
    //   threads.foreach(_.start())
    //   threads.foreach(_.join()) // all threads are terminated // ensure that Thread terminates, can be blocking in Thread.join()
    //   if (ids.contains(null)) {
    //     throw new Exception("ids contains null")
    //   } // data allocated by threads is not GCed
    //   // Should not segfault when iteration over memory freed by other threads
    //   Seq.fill(iterations * 4)(generateGarbage())
    // }

    // def generateGarbage() = {
    //   scala.util.Random.alphanumeric.take(4096).mkString.take(10)
    // }
  }
}
