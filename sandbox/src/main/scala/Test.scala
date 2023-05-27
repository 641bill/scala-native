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
    println("Hello, World!")
    // Do some tests that allocates a large chunk of memory
    // Allocate a large array
    val array = new Array[Int](1000000)
    println(array.length)

    class Foo(val x: Int)
    // Allocate a large array of user-defined class
    val array2 = new Array[Foo](1000000)
    println(array2.length)
  }
}
