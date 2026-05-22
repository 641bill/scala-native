package scala.scalanative.memory

import scala.language.experimental.captureChecking

object RiftSandboxInternals {
  transparent inline def resetOpenHandleInline[T](
      inline body: (RiftOpenStreamingHandle^) ?=> T
  )(using
      region: RiftOpenStreamingHandle^,
      canReturn: RiftRegion.CanReturnFromRegion[T]
  ): T =
    RiftRegion.resetOpenHandleInline(body)
}
