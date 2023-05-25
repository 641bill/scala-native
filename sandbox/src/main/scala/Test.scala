object Test {
  def main(args: Array[String]): Unit = {
    println("Hello, World!")
    // Do some tests that allocates memory
    val array = new Array[Int](10)
    array(0) = 1
    println(array(0))
    // Allocate a user-defined class
    class Foo(val x: Int)
    val foo = new Foo(42)
    println(foo.x)
  }
}
