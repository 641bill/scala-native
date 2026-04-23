package scala.scalanative.memory

import scala.scalanative.runtime.{RawPtr, RawSize, intrinsic}

/** Placeholder for SafeZone. It's used to avoid linking error when using scala
 *  versions other than scala-next, since the type `SafeZone` is used in the
 *  lowering phase.
 */
private[scalanative] trait SafeZone {

  def isOpen: Boolean

  def isClosed: Boolean = !isOpen

  def checkOpen(): Unit = {
    if (!isOpen)
      throw new IllegalStateException(s"Zone ${this} is already closed.")
  }

  private[scalanative] def close(): Unit

  private[scalanative] def handle: RawPtr

  /** Placeholder for `allocImpl` method, which is used in the alloc: Int ->
   *  SafeZone -> Unit method in Arrays. Similarly, it's needed because the
   *  alloc method is used in the lowering phase.
   */
  private[scalanative] def allocImpl(cls: RawPtr, size: RawSize): RawPtr =
    intrinsic
}
