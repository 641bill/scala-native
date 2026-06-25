package scala.scalanative.nscplugin

import java.net.{URI, URISyntaxException}
import java.nio.file.Paths

import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.plugins._
import dotty.tools.dotc.report
import scala.annotation.nowarn

class ScalaNativePlugin extends StandardPlugin:
  val name: String = "scalanative"
  val description: String = "Scala Native compiler plugin"

  override val optionsHelp: Option[String] =
    Some(
      s"""|
          |  -P:$name:genStaticForwardersForNonTopLevelObjects
          |     Generate static forwarders for non-top-level objects.
          |     This option should be used by codebases that implement JDK classes.
          |     When used together with -Xno-forwarders, this option has no effect.
          |  -P:$name:forceStrictFinalFields
          |     Treat all final fields as if they we're marked with @safePublish.
          |     This option should be used by codebased that rely heavily on Java Final Fields semantics
          |     It should not be required by most of normal Scala code.
          |  -P:$name:positionRelativizationPaths
          |     Change the source file positions in generated outputs based on list of provided paths.
          |     It would strip the prefix of the source file if it matches given path.
          |     Non-absolute paths would be ignored.
          |     Multiple paths should be separated by a single semicolon ';' character.
          |     If none of the patches matches path would be relative to -sourcepath if defined or -sourceroot otherwise.
          |  -P:$name:riftInferReport
          |     Emit opt-in diagnostics for Rift region placement inference decisions.
          |  -P:$name:riftInferAutomaticScopes
          |     Experimental: enable prototype automatic local-escape region scopes.
          |     Off by default; not a validated safety/performance mode.
          |""".stripMargin
    )

  @nowarn("cat=deprecation")
  override def init(options: List[String]): List[PluginPhase] = {
    val (genNirSettings, riftInferenceSettings) = options
      .foldLeft((GenNIR.Settings(), RiftRegionInference.Settings())) {
        case ((genConfig, riftConfig), "genStaticForwardersForNonTopLevelObjects") =>
          genConfig.copy(genStaticForwardersForNonTopLevelObjects = true) ->
            riftConfig
        case ((genConfig, riftConfig), "forceStrictFinalFields") =>
          genConfig.copy(forceStrictFinalFields = true) -> riftConfig
        case ((genConfig, riftConfig), "riftInferReport") =>
          genConfig -> riftConfig.copy(reportDecisions = true)
        case ((genConfig, riftConfig), "riftInferAutomaticScopes") =>
          genConfig -> riftConfig.copy(enableAutomaticRegionScopes = true)
        case ((genConfig, riftConfig), s"positionRelativizationPaths:${paths}") =>
          genConfig.copy(positionRelativizationPaths =
            (genConfig.positionRelativizationPaths ++ paths
              .split(';')
              .map(Paths.get(_))
              .filter(_.isAbsolute())).distinct.sortBy(-_.getNameCount())
          ) -> riftConfig
        case ((genConfig, riftConfig), s"mapSourceURI:${mapping}") =>
          given Context = ContextBase().initialCtx
          report.warning("'mapSourceURI' is deprecated, it's ignored.")
          genConfig -> riftConfig
        case (configs, _) => configs
      }
    List(
      PrepNativeInterop(),
      PostInlineNativeInterop(),
      RiftRegionInference(riftInferenceSettings),
      GenNIR(genNirSettings)
    )
  }
