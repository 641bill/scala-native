import java.lang.Thread
import scala.collection.mutable.ArrayBuffer

object Test {
  def main(args: Array[String]): Unit = {
    for (i <- 0 to 9627) {
      val ab = ArrayBuffer(i, i + 1, i + 2)
    }
    // println("Running GCBench")
    // val benchmarkResult = gcbench.GCBenchBenchmark.run("")
    // println(s"GCBench result: $benchmarkResult")

    // Initialize a thread
    // val thread = new Thread {
    //   override def run(): Unit = {
    //     println("Hello, World! from thread")
    //   }
    // }
    // thread.start()
    // // // Initializa a bunch of threads
    // val threads = (1 to 100).map { i =>
    //   new Thread {
    //     override def run(): Unit = {
    //       println("Hello, World! from thread " + i)
    //     }
    //   }
    // }
    // threads.foreach(_.start())
    // thread.join()
    // threads.foreach(_.join())
    // println("Hello, World!")
    // // Do some tests that allocates a large chunk of memory
    // // Allocate a large array
    // val array = new Array[Int](100000)
    // println(array)
    // println(array.length)

    // class Foo(val x: Int)
    // val array2 = new Array[Foo](100000)
    // import scala.scalanative.runtime.Intrinsics._ 
    // import scala.scalanative.runtime.fromRawPtr 
    // println(fromRawPtr(castObjectToRawPtr(array2)))
    // // println(array2)
    // println(array2.length)
  }
}
