import build.Build
import scala.scalanative.build._

// scalafmt: { align.preset = most}
lazy val scalaNative              = Build.root
lazy val nscPlugin                = Build.nscPlugin
lazy val junitPlugin              = Build.junitPlugin
lazy val sbtScalaNative           = Build.sbtScalaNative
lazy val nir                      = Build.nir
lazy val util                     = Build.util
lazy val tools                    = Build.tools
lazy val nativelib                = Build.nativelib
lazy val clib                     = Build.clib
lazy val posixlib                 = Build.posixlib
lazy val windowslib               = Build.windowslib
lazy val javalib                  = Build.javalib
lazy val javalibExtDummies        = Build.javalibExtDummies
lazy val auxlib                   = Build.auxlib
lazy val scalalib                 = Build.scalalib
lazy val testingCompilerInterface = Build.testingCompilerInterface
lazy val testingCompiler          = Build.testingCompiler
lazy val testInterface            = Build.testInterface
lazy val testInterfaceSbtDefs     = Build.testInterfaceSbtDefs
lazy val testRunner               = Build.testRunner
lazy val junitRuntime             = Build.junitRuntime
lazy val junitTestOutputsNative   = Build.junitTestOutputsNative
lazy val junitTestOutputsJVM      = Build.junitTestOutputsJVM
lazy val junitAsyncNative         = Build.junitAsyncNative
lazy val junitAsyncJVM            = Build.junitAsyncJVM
lazy val tests                    = Build.tests
lazy val testsJVM                 = Build.testsJVM
lazy val testsExt                 = Build.testsExt
lazy val testsExtJVM              = Build.testsExtJVM
lazy val sandbox                  = Build.sandbox
lazy val scalaPartest             = Build.scalaPartest
lazy val scalaPartestTests        = Build.scalaPartestTests
lazy val scalaPartestJunitTests   = Build.scalaPartestJunitTests
lazy val scalaPartestRuntime      = Build.scalaPartestRuntime

commands ++= build.Commands.values
// nativeConfig ~= { c =>
// 	c.withCompileOptions(c.compileOptions ++ Seq(
// 		"-I/home/dcl/mmtk-scala-native/scala-native"))
// 	.withLinkingOptions(c.linkingOptions ++ 
// 		Seq("-L/home/dcl/mmtk-scala-native/mmtk/target/debug") ++
// 		Seq("-lmmtk_scala_native")
// 	)
// 	.withGC(GC.experimental)
// }
inThisBuild(build.Settings.thisBuildSettings)
