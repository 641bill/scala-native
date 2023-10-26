package build

import sbt._
import sbt.Keys._

import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._
import scala.sys.env
import scala.scalanative.build.GC

object MyScalaNativePlugin extends AutoPlugin {
  override def requires: Plugins = ScalaNativePlugin

  final val isGeneratingForIDE =
    env.getOrElse("METALS_ENABLED", "false").toBoolean

  final val enableExperimentalCompiler = {
    val ExperimentalCompilerEnv = "ENABLE_EXPERIMENTAL_COMPILER"
    val enabled = env.contains(ExperimentalCompilerEnv)
    println(
      if (enabled)
        s"Found `$ExperimentalCompilerEnv` env var: enabled sub-projects using Scala experimental version ${ScalaVersions.scala3Nightly}, using suffix `3_next`."
      else
        s"Not found `$ExperimentalCompilerEnv` env var: sub-projects using Scala experimental version would not be available."
    )
    enabled
  }

  // Allowed values: 3, 3-next, 2.13, 2.12
  final val ideScalaVersion = if (enableExperimentalCompiler) "3-next" else "3"

  // Would be visible in Metals logs
  if (isGeneratingForIDE)
    println(s"IDE support enabled using Scala $ideScalaVersion")

  private def multithreadingEnabledBySbtSysProps(): Option[Boolean] = {
    /* Started as: sbt -Dscala.scalanative.multithreading.enable=true
     * That idiom is used by Windows Continuous Integration (CI).
     *
     * BEWARE the root project Quirk!
     * This feature is not meant for general use. Anybody using it
     * should understand how it works.
     *
     * Setting multithreading on the command line __will_ override any
     * such setting in a .sbt file in all projects __except_ the root
     * project. "show nativeConfig" will show the value from .sbt files
     * "show sandbox3/nativeConfig" will show the effective value for
     * non-root projects.
     *
     * Someday this quirk will get fixed.
     */
    sys.props.get("scala.scalanative.multithreading.enable") match {
      case Some(v) => Some(java.lang.Boolean.parseBoolean(v))
      case None    => None
    }
  }

  import sys.process.Process
  import sys.process.ProcessLogger

  lazy val basePath = settingKey[String]("The base path of the project")
  lazy val parentPath = settingKey[String]("The parent path of the project")
  lazy val mmtkBuild = taskKey[Unit]("Build MMTk")
  val root = (project in file("."))

  override def projectSettings: Seq[Setting[_]] = Def.settings(
    basePath := root.base.getAbsolutePath(),
    parentPath := basePath.value + "/..",
    /* Remove libraryDependencies on ourselves; we use .dependsOn() instead
     * inside this build.
     */
    libraryDependencies ~= { libDeps =>
      libDeps.filterNot(_.organization == "org.scala-native")
    },
    nativeConfig ~= { nc =>
      nc.withCheck(true)
        .withCheckFatalWarnings(true)
        .withDump(true)
        .withDebugMetadata(true)
        .withMultithreadingSupport(
          multithreadingEnabledBySbtSysProps()
            .getOrElse(nc.multithreadingSupport)
        )
    },
    nativeConfig := {
      nativeConfig.value.withCompileOptions(
        nativeConfig.value.compileOptions ++
          Seq(s"-I${parentPath.value}/mmtk-scala-native/scala-native") ++
          Seq(s"-g")
      )
      .withLinkingOptions(
        nativeConfig.value.linkingOptions ++ 
        Seq(s"-L${parentPath.value}/mmtk-scala-native/mmtk/target/debug") ++
        Seq("-lmmtk_scala_native")
      )
      .withGC(GC.experimental)
      .withMultithreadingSupport(true)
    },
    // mmtkBuild := {
    //   val s = streams.value
    //   val cmd = "cargo build"
    //   val workingDirectory = new File(s"${parentPath.value}/mmtk-scala-native/mmtk")
    //   val output = new StringBuilder
    //   val error = new StringBuilder
    //   val exitCode = Process(cmd, workingDirectory) ! ProcessLogger(
    //     (out: String) => {
    //       output append out
    //       s.log.info(out)
    //     },
    //     (err: String) => {
    //       error append err
    //       s.log.info(err)
    //     }
    //   )
    //   if (exitCode != 0) {
    //     throw new RuntimeException("MMTk build failed with output: " + error.toString)
    //   }
    // },
    // Compile / compile := (Compile / compile).dependsOn(mmtkBuild).value,
    scalacOptions ++= {
      // Link source maps to GitHub sources
      val isSnapshot = nativeVersion.endsWith("-SNAPSHOT")
      if (isSnapshot) Nil
      else
        Settings.scalaNativeMapSourceURIOption(
          (LocalProject("scala-native") / baseDirectory).value,
          s"https://raw.githubusercontent.com/scala-native/scala-native/v$nativeVersion/"
        )
    }
  )
}
