import java.lang.Thread

object Test {
  def main(args: Array[String]): Unit = {
    // Initialize a thread
    val thread = new Thread {
      override def run(): Unit = {
        println("Hello, World! from thread")
      }
    }
    thread.start()
    // Initializa a bunch of threads
    val threads = (1 to 1000).map { i =>
      new Thread {
        override def run(): Unit = {
          println("Hello, World! from thread " + i)
        }
      }
    }
    threads.foreach(_.start())
    thread.join()
    threads.foreach(_.join())
    println("Hello, World!")
    // Do some tests that allocates a large chunk of memory
    // Allocate a large array
    val array = new Array[Int](1000000)
    println(array.length)

    class Foo(val x: Int)
    val array2 = new Array[Foo](1000000)
    println(array2.length)
  }
}
