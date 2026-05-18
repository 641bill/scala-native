package rift.portable

/** Compatibility entrypoint for earlier prototype smoke commands.
 *
 *  The implementation now lives in the portable API/backends plus
 *  `PortableRiftSmoke`.
 */
object PortableRiftBackendPrototype:
  def main(args: Array[String]): Unit =
    PortableRiftSmoke.main(args)
