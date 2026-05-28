package scala.scalanative.nscplugin

import dotty.tools.dotc._
import dotty.tools.dotc.ast.tpd._
import dotty.tools.dotc.cc.{CaptureSet, CapturingType}
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.backend.jvm.DottyBackendInterface.symExtensions

import scala.scalanative.nscplugin.CompilerCompat.SymUtilsCompat.*
import scala.collection.mutable

object RiftRegionInference {
  val name = "scalanative-riftRegionInference"

  final case class Settings(reportDecisions: Boolean = false)

  sealed trait AllocationOwner
  object AllocationOwner {
    case object Heap extends AllocationOwner
    final case class Region(owner: Symbol) extends AllocationOwner
    case object Unknown extends AllocationOwner
    case object Rejected extends AllocationOwner
  }

  final case class AllocationDecision(
      owner: AllocationOwner,
      reason: String,
      pos: dotty.tools.dotc.util.SrcPos
  )

  private[nscplugin] val inferredAllocationOwners
      : mutable.Map[Symbol, Symbol] =
    mutable.Map.empty
  private[nscplugin] val inferredMethodReturnOwners
      : mutable.Map[Symbol, Symbol] =
    mutable.Map.empty
  private[nscplugin] val inferredMethodReturnOwnersBySourceSpan
      : mutable.Map[(String, Int, Int), Symbol] =
    mutable.Map.empty
  private[nscplugin] val inferredMethodReturnOwnersBySourceLine
      : mutable.Map[(String, String, Int), mutable.Set[Symbol]] =
    mutable.Map.empty
  private[nscplugin] val inferredMethodReturnLocalOwners
      : mutable.Map[Symbol, Symbol] =
    mutable.Map.empty
  private[nscplugin] val inferredClosureBodyOwners
      : mutable.Map[Symbol, Symbol] =
    mutable.Map.empty
  private[nscplugin] val inferredClosureValueOwners
      : mutable.Map[Symbol, Symbol] =
    mutable.Map.empty
  private[nscplugin] val inferredArrayElementOwners
      : mutable.Map[Symbol, Symbol] =
    mutable.Map.empty
  private[nscplugin] val allocationDecisions
      : mutable.Map[Symbol, AllocationDecision] =
    mutable.Map.empty
  private[nscplugin] val inferredAllocationOwnersBySourceSpan
      : mutable.Map[(String, Int, Int), Symbol] =
    mutable.Map.empty
  private[nscplugin] val inferredClosureOwnersBySourceSpan
      : mutable.Map[(String, Int, Int), Symbol] =
    mutable.Map.empty
  private[nscplugin] val inferredClosureOwnersBySourceLine
      : mutable.Map[(String, Int), mutable.Set[Symbol]] =
    mutable.Map.empty
  // Allocation effect tracking: maps closure symbols to their allocation
  // effect owner. A closure has an allocation effect when its expected type
  // has a captured owner (e.g., Function1[Int, T]^{r}), meaning the closure
  // body should be able to allocate in region r even if it doesn't explicitly
  // capture r. This is the ReML-style effect polymorphism mechanism.
  private[nscplugin] val inferredClosureAllocationEffects
      : mutable.Map[Symbol, Symbol] =
    mutable.Map.empty
  // Source-span-based allocation effect tracking for lambda-lifted closures
  // where the original symbol may not survive lambda lifting.
  private[nscplugin] val inferredClosureAllocationEffectsBySourceSpan
      : mutable.Map[(String, Int, Int), Symbol] =
    mutable.Map.empty

  // Escape analysis for automatic region scope inference.
  // Tracks the escape behavior of each allocation site.
  sealed trait EscapeBehavior
  object EscapeBehavior {
    // Object never escapes the current method (local-escape)
    case object Local extends EscapeBehavior
    // Object escapes to the heap (heap-escape)
    case object Heap extends EscapeBehavior
    // Object escapes to another region (region-escape)
    case class Region(owner: Symbol) extends EscapeBehavior
    // Escape behavior not yet determined
    case object Unknown extends EscapeBehavior
  }

  // Maps allocation sites to their escape behavior.
  private[nscplugin] val allocationEscapeBehavior
      : mutable.Map[Symbol, EscapeBehavior] =
    mutable.Map.empty

  // Maps allocation sites to the set of locations they can reach.
  // Used for escape analysis to determine if objects are local-escape.
  private[nscplugin] val allocationReachSet
      : mutable.Map[Symbol, mutable.Set[Symbol]] =
    mutable.Map.empty

  private[nscplugin] def sourceSpanKey(
      pos: dotty.tools.dotc.util.SrcPos
  )(using dotty.tools.dotc.core.Contexts.Context): Option[(String, Int, Int)] = {
    val sourcePos = pos.sourcePos
    if sourcePos.span.exists && sourcePos.source.exists then
      Some(
        (
          sourcePos.source.file.absolute.jpath.toString,
          sourcePos.span.start,
          sourcePos.span.end
        )
      )
    else None
  }

  private[nscplugin] def normalizedMethodName(name: String): String =
    name.replaceAll("\\$\\d+$", "")

  private[nscplugin] def sourceLineKey(
      pos: dotty.tools.dotc.util.SrcPos,
      methodName: String
  )(using dotty.tools.dotc.core.Contexts.Context): Option[(String, String, Int)] = {
    val sourcePos = pos.sourcePos
    if sourcePos.span.exists && sourcePos.source.exists then
      Some(
        (
          sourcePos.source.file.absolute.jpath.toString,
          normalizedMethodName(methodName),
          sourcePos.startLine
        )
      )
    else None
  }

  private[nscplugin] def sourceLineKey(
      pos: dotty.tools.dotc.util.SrcPos
  )(using dotty.tools.dotc.core.Contexts.Context): Option[(String, Int)] = {
    val sourcePos = pos.sourcePos
    if sourcePos.span.exists && sourcePos.source.exists then
      Some(
        (
          sourcePos.source.file.absolute.jpath.toString,
          sourcePos.startLine
        )
      )
    else None
  }

  // Track methods that have automatic region scopes
  private[nscplugin] val methodsWithAutomaticRegionScopes
      : mutable.Map[Symbol, Symbol] =
    mutable.Map.empty

  private def markMethodHasAutomaticRegionScopes(
      methodSym: Symbol,
      regionSym: Symbol
  ): Unit = {
    methodsWithAutomaticRegionScopes.update(methodSym, regionSym)
  }

  // Check if a method has automatic region scopes
  private[nscplugin] def hasAutomaticRegionScopes(
      methodSym: Symbol
  ): Boolean =
    methodsWithAutomaticRegionScopes.contains(methodSym)

  // Get the synthetic region symbol for a method
  private[nscplugin] def getAutomaticRegionSymbol(
      methodSym: Symbol
  ): Option[Symbol] =
    methodsWithAutomaticRegionScopes.get(methodSym)
}

/** Capture-directed placement for the first ReML-style Rift inference slices.
 *
 *  This phase deliberately does not infer new region boundaries. It marks
 *  direct `new T(...)` sites whose
 *  expected type is captured by an explicit checked `ScopedRegion` or
 *  `OpenStreamingRegion`, and direct immutable local allocation values that
 *  are immediately constrained by a checked RegionList prepend owner. GenNIR
 *  later turns the mark into a Rift allocation zone.
 *  Parent `StreamingRegion` values are excluded in v1 because page/window
 *  operators need child-bucket placement, not just the parent stream capture.
 */
class RiftRegionInference(
    settings: RiftRegionInference.Settings =
      RiftRegionInference.Settings()
) extends PluginPhase {
  import core.Contexts._
  import core.Flags._
  import core.Names._
  import core.StdNames._
  import core.Symbols._
  import core.Types._

  override val runsAfter = Set(PostInlineNativeInterop.name)
  override val runsBefore = Set(transform.FirstTransform.name)
  val phaseName = RiftRegionInference.name
  override def description: String =
    "mark capture-directed Rift region allocation sites"

  override def runOn(
      units: List[CompilationUnit]
  )(using Context): List[CompilationUnit] = {
    RiftRegionInference.inferredAllocationOwners.clear()
    RiftRegionInference.inferredMethodReturnOwners.clear()
    RiftRegionInference.inferredMethodReturnOwnersBySourceSpan.clear()
    RiftRegionInference.inferredMethodReturnOwnersBySourceLine.clear()
    RiftRegionInference.inferredMethodReturnLocalOwners.clear()
    RiftRegionInference.inferredClosureBodyOwners.clear()
    RiftRegionInference.inferredClosureValueOwners.clear()
    RiftRegionInference.inferredArrayElementOwners.clear()
    RiftRegionInference.allocationDecisions.clear()
    RiftRegionInference.inferredAllocationOwnersBySourceSpan.clear()
    RiftRegionInference.inferredClosureOwnersBySourceSpan.clear()
    RiftRegionInference.inferredClosureOwnersBySourceLine.clear()
    RiftRegionInference.inferredClosureAllocationEffects.clear()
    RiftRegionInference.inferredClosureAllocationEffectsBySourceSpan.clear()
    RiftRegionInference.allocationEscapeBehavior.clear()
    RiftRegionInference.allocationReachSet.clear()
    RiftRegionInference.methodsWithAutomaticRegionScopes.clear()
    directlyNewAllocatedSyms.clear()
    localRegionConstructAllocatedApps.clear()
    localAllocatedSyms.clear()
    localClosureAllocatedSyms.clear()
    localNestedClosureAllocatedSyms.clear()
    directClosureAllocatedSyms.clear()
    childRegionOwnerSyms.clear()
    localOwnerAliases.clear()
    localCaptureOwnerSymsByName.clear()
    localArrayElementOwnerSyms.clear()
    localStreamRankArrayElementOwnerSyms.clear()
    val result = super.runOn(units)
    if settings.reportDecisions then reportInferenceDecisions()
    result
  }

  private val directlyNewAllocatedSyms: mutable.Set[Symbol] =
    mutable.Set.empty
  private val localRegionConstructAllocatedApps
      : mutable.Map[Symbol, List[Apply]] =
    mutable.Map.empty
  private val localAllocatedSyms: mutable.Map[Symbol, List[Symbol]] =
    mutable.Map.empty
  private val localClosureAllocatedSyms
      : mutable.Map[Symbol, List[(Symbol, Closure)]] =
    mutable.Map.empty
  private sealed trait NestedClosureAllocation
  private final case class DirectNestedClosure(closure: Closure)
      extends NestedClosureAllocation
  private final case class LocalNestedClosureAlias(target: Symbol)
      extends NestedClosureAllocation
  private final case class LocalNestedClosure(target: Symbol, closure: Closure)
      extends NestedClosureAllocation
  private val localNestedClosureAllocatedSyms
      : mutable.Map[Symbol, List[NestedClosureAllocation]] =
    mutable.Map.empty
  private val directClosureAllocatedSyms: mutable.Map[Symbol, Closure] =
    mutable.Map.empty
  private val childRegionOwnerSyms: mutable.Set[Symbol] =
    mutable.Set.empty
  private val localOwnerAliases: mutable.Map[Symbol, Symbol] =
    mutable.Map.empty
  private val localCaptureOwnerSymsByName
      : mutable.Map[String, mutable.Set[Symbol]] =
    mutable.Map.empty
  private val localArrayElementOwnerSyms: mutable.Map[Symbol, Symbol] =
    mutable.Map.empty
  private val localStreamRankArrayElementOwnerSyms
      : mutable.Map[Symbol, Symbol] =
    mutable.Map.empty

  private def updateDecision(
      target: Symbol,
      owner: RiftRegionInference.AllocationOwner,
      reason: String,
      pos: dotty.tools.dotc.util.SrcPos
  ): Unit =
    RiftRegionInference.allocationDecisions.update(
      target,
      RiftRegionInference.AllocationDecision(owner, reason, pos)
    )

  private def markRejected(
      target: Symbol,
      reason: String,
      pos: dotty.tools.dotc.util.SrcPos
  ): Unit = {
    RiftRegionInference.inferredAllocationOwners.remove(target)
    updateDecision(
      target,
      RiftRegionInference.AllocationOwner.Rejected,
      reason,
      pos
    )
  }

  private def markRegionOwner(
      target: Symbol,
      owner: Symbol,
      reason: String,
      pos: dotty.tools.dotc.util.SrcPos
  )(using Context): Unit = {
    val canonical = canonicalOwner(owner)
    val existing = RiftRegionInference.inferredAllocationOwners.get(target)
    existing match {
      case Some(previous) if canonicalOwner(previous) != canonical =>
        markRejected(
          target,
          s"conflicting inferred region owners: ${previous.name} and ${owner.name}",
          pos
        )
      case _ =>
        RiftRegionInference.inferredAllocationOwners.update(target, canonical)
        updateDecision(
          target,
          RiftRegionInference.AllocationOwner.Region(canonical),
          reason,
          pos
        )
    }
  }

  private def markLocalRegionConstructOwner(
      target: Symbol,
      owner: Symbol,
      reason: String,
      pos: dotty.tools.dotc.util.SrcPos
  )(using Context): Unit = {
    markRegionOwner(target, owner, reason, pos)
    localRegionConstructAllocatedApps.get(target).foreach { apps =>
      apps.foreach(app => markDirectConstructOwner(app, owner, reason))
    }
  }

  private def treeMentionsRuntimeOwnerValue(
      tree: Tree,
      owner: Symbol
  )(using Context): Boolean = {
    val canonical = canonicalOwner(owner)

    def matchesOwner(sym: Symbol): Boolean =
      sym != NoSymbol &&
        (canonicalOwner(sym) == canonical ||
          localOwnerAliases
            .get(sym)
            .map(canonicalOwner)
            .contains(canonical))

    def loop(current: Tree): Boolean =
      current match {
        case _: TypeTree =>
          false
        case Ident(_) =>
          matchesOwner(current.symbol)
        case Select(qualifier, _) =>
          matchesOwner(current.symbol) || loop(qualifier)
        case Apply(fun, args) =>
          loop(fun) || args.exists(loop)
        case TypeApply(fun, _) =>
          loop(fun)
        case vd: ValDef =>
          loop(vd.rhs)
        case dd: DefDef =>
          loop(dd.rhs)
        case Block(stats, expr) =>
          stats.exists(loop) || loop(expr)
        case Typed(expr, _) =>
          loop(expr)
        case Inlined(_, _, expr) =>
          loop(expr)
        case Return(expr, _) =>
          loop(expr)
        case Labeled(_, body) =>
          loop(body)
        case Assign(lhs, rhs) =>
          loop(lhs) || loop(rhs)
        case If(cond, thenp, elsep) =>
          loop(cond) || loop(thenp) || loop(elsep)
        case Match(selector, cases) =>
          loop(selector) || cases.exists {
            case CaseDef(_, guard, body) => loop(guard) || loop(body)
          }
        case Closure(env, fun, _) =>
          env.exists(loop) || loop(fun)
        case Try(expr, cases, finalizer) =>
          loop(expr) ||
            cases.exists { case CaseDef(_, guard, body) =>
              loop(guard) || loop(body)
            } ||
            loop(finalizer)
        case WhileDo(cond, body) =>
          loop(cond) || loop(body)
        case _ =>
          false
      }

    loop(tree)
  }

  private def markDirectConstructOwner(
      app: Apply,
      owner: Symbol,
      reason: String
  )(using Context): Unit = {
    val canonical = canonicalOwner(owner)
    app.putAttachment(
      NirDefinitions.InferredRiftAllocationOwner,
      canonical
    )
    RiftRegionInference.sourceSpanKey(app.srcPos).foreach { key =>
      RiftRegionInference.inferredAllocationOwnersBySourceSpan.update(
        key,
        canonical
      )
    }
    updateDecision(
      calledSymbol(app),
      RiftRegionInference.AllocationOwner.Region(canonical),
      reason,
      app.srcPos
    )
  }

  private def markMethodReturnOwner(dd: DefDef, owner: Symbol)(using Context): Unit = {
    val canonical = canonicalOwner(owner)
    RiftRegionInference.inferredMethodReturnOwners.update(dd.symbol, canonical)
    RiftRegionInference.sourceSpanKey(dd.srcPos).foreach { key =>
      RiftRegionInference.inferredMethodReturnOwnersBySourceSpan.update(
        key,
        canonical
      )
    }
    RiftRegionInference
      .sourceLineKey(dd.srcPos, dd.symbol.name.toString)
      .foreach { key =>
        val owners =
          RiftRegionInference.inferredMethodReturnOwnersBySourceLine
            .getOrElseUpdate(key, mutable.Set.empty)
        owners += canonical
      }
  }

  private def markClosureSourceOwner(
      closure: Closure,
      owner: Symbol
  )(using Context): Unit = {
    val canonical = canonicalOwner(owner)
    RiftRegionInference.sourceSpanKey(closure.srcPos).foreach { key =>
      RiftRegionInference.inferredClosureOwnersBySourceSpan.update(
        key,
        canonical
      )
    }
    RiftRegionInference.sourceLineKey(closure.srcPos).foreach { key =>
      val owners =
        RiftRegionInference.inferredClosureOwnersBySourceLine
          .getOrElseUpdate(key, mutable.Set.empty)
      owners += canonical
    }
  }

  private def registerLocalCaptureOwner(sym: Symbol)(using Context): Unit =
    if sym != NoSymbol then
      val owners =
        localCaptureOwnerSymsByName.getOrElseUpdate(
          sym.name.toString,
          mutable.Set.empty
        )
      owners += sym

  private def canonicalOwner(sym: Symbol)(using Context): Symbol =
    if sym == NoSymbol then NoSymbol
    else
      localOwnerAliases.get(sym) match {
        case Some(next) if next != sym => canonicalOwner(next)
        case _                         => sym
      }

  private def reportInferenceDecisions()(using Context): Unit = {
    RiftRegionInference.allocationDecisions.toList
      .sortBy((sym, _) => sym.fullName.toString)
      .foreach { (sym, decision) =>
        val owner = decision.owner match {
          case RiftRegionInference.AllocationOwner.Heap =>
            "Heap"
          case RiftRegionInference.AllocationOwner.Region(owner) =>
            s"Region(${owner.name})"
          case RiftRegionInference.AllocationOwner.Unknown =>
            "Unknown"
          case RiftRegionInference.AllocationOwner.Rejected =>
            "Rejected"
        }
        Console.err.println(
          s"[rift-infer] ${decision.pos}: ${sym.name} -> ${owner}: ${decision.reason}"
        )
      }
  }

  private def isRiftScopedRegionType(tpe: Type)(using Context): Boolean = {
    val scopedRegionName =
      "scala.scalanative.memory.RiftRegion.ScopedRegion"
    val widened = tpe.widenDealias
    !tpe.isInstanceOf[MethodType] &&
      (widened.typeSymbol.fullName.toString == scopedRegionName ||
        tpe.show.contains(scopedRegionName) ||
        widened.show.contains(scopedRegionName))
  }

  private def isRiftOpenStreamingRegionType(tpe: Type)(using Context): Boolean = {
    val openRegionName =
      "scala.scalanative.memory.RiftRegion.OpenStreamingRegion"
    val widened = tpe.widenDealias
    !tpe.isInstanceOf[MethodType] &&
      (widened.typeSymbol.fullName.toString == openRegionName ||
        tpe.show.contains(openRegionName) ||
        widened.show.contains(openRegionName))
  }

  private def isRiftStreamingRegionType(tpe: Type)(using Context): Boolean = {
    val streamingRegionName =
      "scala.scalanative.memory.RiftRegion.StreamingRegion"
    val widened = tpe.widenDealias
    !tpe.isInstanceOf[MethodType] &&
      (widened.typeSymbol.fullName.toString == streamingRegionName ||
        tpe.show.contains(streamingRegionName) ||
        widened.show.contains(streamingRegionName))
  }

  private def isRiftOpenStreamingHandleType(tpe: Type)(using Context): Boolean = {
    val handleName = "scala.scalanative.memory.RiftOpenStreamingHandle"
    val widened = tpe.widenDealias
    !tpe.isInstanceOf[MethodType] &&
      (widened.typeSymbol.fullName.toString == handleName ||
        tpe.show.contains(handleName) ||
        widened.show.contains(handleName))
  }

  private def isRiftInferredAllocationOwnerType(tpe: Type)(using Context): Boolean =
    isRiftScopedRegionType(tpe) ||
      isRiftOpenStreamingRegionType(tpe) ||
      isRiftOpenStreamingHandleType(tpe)

  private def isRiftInferredAllocationOwnerSymbol(
      sym: Symbol
  )(using Context): Boolean =
    val owner = canonicalOwner(sym)
    owner != NoSymbol &&
      !sym.is(Method) &&
      !owner.is(Method) &&
      (isRiftInferredAllocationOwnerType(sym.info) ||
        isRiftInferredAllocationOwnerType(owner.info) ||
        childRegionOwnerSyms.contains(sym) ||
        childRegionOwnerSyms.contains(owner) ||
        localOwnerAliases.contains(sym))

  private def isRiftFrameworkOwnerTokenSymbol(
      sym: Symbol
  )(using Context): Boolean = {
    val owner = canonicalOwner(sym)
    owner != NoSymbol &&
      !sym.is(Method) &&
      !owner.is(Method) &&
      (isRiftInferredAllocationOwnerSymbol(sym) ||
        isRiftStreamingRegionType(sym.info) ||
        isRiftStreamingRegionType(owner.info))
  }

  private def typeMentionsRiftCapture(tpe: Type)(using Context): Boolean =
    List(tpe.show, tpe.widenDealias.show).exists(text =>
      text.contains("^{") || text.contains("->{")
    )

  private def isRuntimeRiftAllocateName(name: String): Boolean =
    name == "allocate" ||
      name == "allocateOpen" ||
      name == "allocateOpenHandle" ||
      name == "allocateOpenHandleNoZero"

  private def isRiftRegionImplementationSource(tree: Tree)(using Context): Boolean =
    tree.sourcePos.span.exists &&
      tree.sourcePos.source.exists &&
      tree.sourcePos.source.file.absolute.jpath.toString
        .endsWith("scala/scalanative/memory/RiftRegion.scala")

  private def isRiftRegionRuntimeAllocatorForwarderArg(
      callee: Symbol,
      value: Tree
  )(using Context): Boolean =
    isRiftRegionImplementationSource(value) &&
      isRuntimeRiftAllocateName(callee.name.toString)

  private def directNewApply(tree: Tree): Option[Apply] =
    tree match {
      case app @ Apply(Select(New(_), nme.CONSTRUCTOR), _) => Some(app)
      case app @ Apply(TypeApply(Select(New(_), nme.CONSTRUCTOR), _), _) =>
        Some(app)
      case Typed(expr, _)                                  => directNewApply(expr)
      case Inlined(_, _, expr)                             => directNewApply(expr)
      case Block(_, expr)                                  => directNewApply(expr)
      case _                                               => None
    }

  private def directSomeApply(tree: Tree)(using Context): Option[Apply] =
    tree match {
      case app @ Apply(_, _ :: Nil) if isScalaSomeApply(calledSymbol(app)) =>
        Some(app)
      case Typed(expr, _)      => directSomeApply(expr)
      case Inlined(_, _, expr) => directSomeApply(expr)
      case Block(_, expr)      => directSomeApply(expr)
      case _                   => None
    }

  private def directOptionApply(tree: Tree)(using Context): Option[Apply] =
    tree match {
      case app @ Apply(_, _ :: Nil) if isScalaOptionApply(calledSymbol(app)) =>
        Some(app)
      case Typed(expr, _)      => directOptionApply(expr)
      case Inlined(_, _, expr) => directOptionApply(expr)
      case Block(_, expr)      => directOptionApply(expr)
      case _                   => None
    }

  private def directEitherApply(tree: Tree)(using Context): Option[Apply] =
    tree match {
      case app @ Apply(_, _ :: Nil) if isScalaEitherApply(calledSymbol(app)) =>
        Some(app)
      case Typed(expr, _)      => directEitherApply(expr)
      case Inlined(_, _, expr) => directEitherApply(expr)
      case Block(_, expr)      => directEitherApply(expr)
      case _                   => None
    }

  private def directTupleApply(tree: Tree)(using Context): Option[Apply] =
    tree match {
      case app @ Apply(_, args) if isScalaTupleApply(calledSymbol(app), args.size) =>
        Some(app)
      case Typed(expr, _)      => directTupleApply(expr)
      case Inlined(_, _, expr) => directTupleApply(expr)
      case Block(_, expr)      => directTupleApply(expr)
      case _                   => None
    }

  private def directArrayApply(tree: Tree)(using Context): Option[Apply] =
    tree match {
      case app @ Apply(_, _) if isScalaRuntimeArraysNewArray(calledSymbol(app)) =>
        Some(app)
      case TypeApply(Select(expr, nme.asInstanceOf_), _) =>
        directArrayApply(expr)
      case Typed(expr, _)      => directArrayApply(expr)
      case Inlined(_, _, expr) => directArrayApply(expr)
      case Block(_, expr)      => directArrayApply(expr)
      case _                   => None
    }

  private def directClosure(tree: Tree): Option[Closure] =
    tree match {
      case closure: Closure                              => Some(closure)
      case Typed(expr, _)                                => directClosure(expr)
      case Inlined(_, _, expr)                           => directClosure(expr)
      case Block(_, expr)                                => directClosure(expr)
      case TypeApply(Select(expr, nme.asInstanceOf_), _) => directClosure(expr)
      case _                                             => None
    }

  private def directReturnedClosures(tree: Tree)(using Context): List[Closure] =
    directClosure(tree).toList match {
      case found @ (_ :: _) => found
      case Nil =>
        tree match {
          case Typed(expr, _) =>
            directReturnedClosures(expr)
          case Inlined(_, _, expr) =>
            directReturnedClosures(expr)
          case Block(_, expr) =>
            directReturnedClosures(expr)
          case TypeApply(Select(expr, nme.asInstanceOf_), _) =>
            directReturnedClosures(expr)
          case If(_, thenp, elsep) =>
            directReturnedClosures(thenp) :::
              directReturnedClosures(elsep)
          case Match(_, cases) =>
            cases.flatMap { case CaseDef(_, _, body) =>
              directReturnedClosures(body)
            }
          case _ =>
            Nil
        }
    }

  private def localClosureAllocationPairs(
      target: Symbol
  ): List[(Symbol, Closure)] =
    localClosureAllocatedSyms.getOrElse(
      target,
      directClosureAllocatedSyms.get(target).map(target -> _).toList
    )

  private def nestedClosureAllocations(
      valueTree: Tree
  )(using Context): List[NestedClosureAllocation] = {
    val treeLocalClosurePairs = localClosureAllocationPairsInTree(valueTree)
    def closurePairs(target: Symbol): List[(Symbol, Closure)] =
      treeLocalClosurePairs.getOrElse(target, localClosureAllocationPairs(target))

    def loop(tree: Tree): List[NestedClosureAllocation] =
      directRegionConstructApply(tree) match {
      case Some(app) =>
        app.args.flatMap { arg =>
          val localClosureAliases =
            returnedLocalIdents(arg).flatMap { target =>
              val pairs = closurePairs(target)
              if pairs.nonEmpty && !pairs.exists(_._1 == target) then
                LocalNestedClosureAlias(target) :: Nil
              else Nil
            }
          directReturnedClosures(arg).map(DirectNestedClosure.apply) :::
            localClosureAliases :::
            returnedLocalIdents(arg)
              .flatMap(closurePairs)
              .map { case (target, closure) =>
                LocalNestedClosure(target, closure)
              } :::
            loop(arg)
        }.distinct
      case None =>
        tree match {
          case Typed(expr, _) =>
            loop(expr)
          case Inlined(_, _, expr) =>
            loop(expr)
          case Block(_, expr) =>
            loop(expr)
          case TypeApply(Select(expr, nme.asInstanceOf_), _) =>
            loop(expr)
          case Return(expr, _) =>
            loop(expr)
          case Labeled(_, body) =>
            loop(body)
          case If(_, thenp, elsep) =>
            loop(thenp) ::: loop(elsep)
          case Match(_, cases) =>
            cases.flatMap { case CaseDef(_, _, body) =>
              loop(body)
            }
          case _ =>
            Nil
        }
    }
    loop(valueTree)
  }

  private def localClosureAllocationPairsInTree(
      tree: Tree
  )(using Context): Map[Symbol, List[(Symbol, Closure)]] = {
    val found = mutable.Map.empty[Symbol, List[(Symbol, Closure)]]

    def pairsFor(target: Symbol): List[(Symbol, Closure)] =
      found.getOrElse(target, localClosureAllocationPairs(target))

    def scan(current: Tree): Unit =
      current match {
        case vd: ValDef =>
          scan(vd.rhs)
          if !vd.symbol.is(Mutable) then {
            val pairs =
              directClosure(vd.rhs)
                .map(closure => (vd.symbol -> closure) :: Nil)
                .getOrElse(
                  returnedLocalIdents(vd.rhs).flatMap(pairsFor).distinct
                )
            if pairs.nonEmpty then found.update(vd.symbol, pairs)
          }
        case Block(stats, expr) =>
          stats.foreach(scan)
          scan(expr)
        case Typed(expr, _) =>
          scan(expr)
        case Inlined(_, _, expr) =>
          scan(expr)
        case TypeApply(Select(expr, nme.asInstanceOf_), _) =>
          scan(expr)
        case Return(expr, _) =>
          scan(expr)
        case Labeled(_, body) =>
          scan(body)
        case If(_, thenp, elsep) =>
          scan(thenp)
          scan(elsep)
        case Match(_, cases) =>
          cases.foreach { case CaseDef(_, _, body) => scan(body) }
        case _ => ()
      }

    scan(tree)
    found.toMap
  }

  private def localAllocationTargets(target: Symbol): List[Symbol] =
    localAllocatedSyms.getOrElse(
      target,
      if directlyNewAllocatedSyms.contains(target) then target :: Nil else Nil
    )

  private def markSelectedAliasOwner(
      target: Symbol,
      allocationTargets: List[Symbol],
      owner: Symbol,
      reason: String,
      pos: dotty.tools.dotc.util.SrcPos
  )(using Context): Unit =
    if allocationTargets.nonEmpty && !allocationTargets.contains(target) then
      markRegionOwner(target, owner, reason, pos)

  private def markLocalAllocationTargets(
      target: Symbol,
      owner: Symbol,
      reason: String,
      aliasReason: String,
      pos: dotty.tools.dotc.util.SrcPos
  )(using Context): Unit = {
    val allocationTargets = localAllocationTargets(target).distinct
    markSelectedAliasOwner(target, allocationTargets, owner, aliasReason, pos)
    allocationTargets.foreach { allocationTarget =>
      markLocalRegionConstructOwner(allocationTarget, owner, reason, pos)
      localNestedClosureAllocatedSyms
        .get(allocationTarget)
        .foreach { nested =>
          markNestedClosureOwnerConstrainedAllocations(
            owner,
            nested,
            "nested direct closure value is constrained by a checked region owner",
            "nested local closure value is constrained by a checked region owner",
            "nested closure body return is constrained by a checked region owner",
            "conflicting inferred nested local closure owners",
            pos
          )
        }
    }
  }

  private def markSelectedLocalAllocationTargets(
      target: Symbol,
      owner: Symbol,
      reason: String,
      aliasReason: String,
      pos: dotty.tools.dotc.util.SrcPos
  )(using Context): Unit = {
    val allocationTargets =
      localAllocatedSyms.getOrElse(target, Nil).distinct match {
        case targets if targets.sizeIs > 1 => targets
        case _                             => Nil
      }
    markSelectedAliasOwner(target, allocationTargets, owner, aliasReason, pos)
    allocationTargets.foreach { allocationTarget =>
      markLocalRegionConstructOwner(allocationTarget, owner, reason, pos)
      localNestedClosureAllocatedSyms
        .get(allocationTarget)
        .foreach { nested =>
          markNestedClosureOwnerConstrainedAllocations(
            owner,
            nested,
            "nested selected direct closure value is constrained by a checked region owner",
            "nested selected local closure value is constrained by a checked region owner",
            "nested selected closure body return is constrained by a checked region owner",
            "conflicting inferred nested selected local closure owners",
            pos
          )
        }
    }
  }

  private def directRegionConstructApply(tree: Tree)(using Context): Option[Apply] =
    directNewApply(tree)
      .orElse(directSomeApply(tree))
      .orElse(directOptionApply(tree))
      .orElse(directEitherApply(tree))
      .orElse(directTupleApply(tree))
      .orElse(directArrayApply(tree))

  private def directReturnedNewApplies(tree: Tree)(using Context): List[Apply] =
    directRegionConstructApply(tree).toList match {
      case found @ (_ :: _) => found
      case Nil =>
        tree match {
          case Typed(expr, _) =>
            directReturnedNewApplies(expr)
          case Inlined(_, _, expr) =>
            directReturnedNewApplies(expr)
          case Block(_, expr) =>
            directReturnedNewApplies(expr)
          case TypeApply(Select(expr, nme.asInstanceOf_), _) =>
            directReturnedNewApplies(expr)
          case Return(expr, _) =>
            directReturnedNewApplies(expr)
          case Labeled(_, body) =>
            directReturnedNewApplies(body)
          case If(_, thenp, elsep) =>
            directReturnedNewApplies(thenp) :::
              directReturnedNewApplies(elsep)
          case Match(_, cases) =>
            cases.flatMap { case CaseDef(_, _, body) =>
              directReturnedNewApplies(body)
            }
          case _ =>
            Nil
        }
    }

  private def markNestedLocalOwnerConstrainedAllocations(
      owner: Symbol,
      valueTree: Tree,
      reason: String,
      aliasReason: String
  )(using Context): Unit =
    directRegionConstructApply(valueTree) match {
      case Some(app) =>
        app.args.foreach { arg =>
          returnedLocalIdents(arg).distinct.foreach { target =>
            markSelectedLocalAllocationTargets(
              target,
              owner,
              reason,
              aliasReason,
              arg.srcPos
            )
          }
          markNestedLocalOwnerConstrainedAllocations(
            owner,
            arg,
            reason,
            aliasReason
          )
        }
      case None =>
        valueTree match {
          case Typed(expr, _) =>
            markNestedLocalOwnerConstrainedAllocations(
              owner,
              expr,
              reason,
              aliasReason
            )
          case Inlined(_, _, expr) =>
            markNestedLocalOwnerConstrainedAllocations(
              owner,
              expr,
              reason,
              aliasReason
            )
          case Block(_, expr) =>
            markNestedLocalOwnerConstrainedAllocations(
              owner,
              expr,
              reason,
              aliasReason
            )
          case TypeApply(Select(expr, nme.asInstanceOf_), _) =>
            markNestedLocalOwnerConstrainedAllocations(
              owner,
              expr,
              reason,
              aliasReason
            )
          case Return(expr, _) =>
            markNestedLocalOwnerConstrainedAllocations(
              owner,
              expr,
              reason,
              aliasReason
            )
          case Labeled(_, body) =>
            markNestedLocalOwnerConstrainedAllocations(
              owner,
              body,
              reason,
              aliasReason
            )
          case If(_, thenp, elsep) =>
            markNestedLocalOwnerConstrainedAllocations(
              owner,
              thenp,
              reason,
              aliasReason
            )
            markNestedLocalOwnerConstrainedAllocations(
              owner,
              elsep,
              reason,
              aliasReason
            )
          case Match(_, cases) =>
            cases.foreach { case CaseDef(_, _, body) =>
              markNestedLocalOwnerConstrainedAllocations(
                owner,
                body,
                reason,
                aliasReason
              )
            }
          case _ => ()
        }
    }

  private def directReturnedInferredMethodCalls(tree: Tree)(using Context): List[Apply] =
    tree match {
      case app: Apply
          if RiftRegionInference.inferredMethodReturnOwners.contains(
            calledSymbol(app)
          ) =>
        app :: Nil
      case Typed(expr, _) =>
        directReturnedInferredMethodCalls(expr)
      case Inlined(_, _, expr) =>
        directReturnedInferredMethodCalls(expr)
      case Block(_, expr) =>
        directReturnedInferredMethodCalls(expr)
      case TypeApply(Select(expr, nme.asInstanceOf_), _) =>
        directReturnedInferredMethodCalls(expr)
      case If(_, thenp, elsep) =>
        directReturnedInferredMethodCalls(thenp) :::
          directReturnedInferredMethodCalls(elsep)
      case Match(_, cases) =>
        cases.flatMap { case CaseDef(_, _, body) =>
          directReturnedInferredMethodCalls(body)
        }
      case _ =>
        Nil
    }

  private def isRiftRegionCompanionOwner(sym: Symbol)(using Context): Boolean =
    sym.owner.fullName.toString.stripSuffix("$") ==
      "scala.scalanative.memory.RiftRegion"

  private def calledSymbol(tree: Tree)(using Context): Symbol =
    tree match {
      case Apply(fun, _)     => calledSymbol(fun)
      case TypeApply(fun, _) => calledSymbol(fun)
      case Select(_, _)      => tree.symbol
      case _                 => tree.symbol
    }

  private def isScalaSomeApply(sym: Symbol)(using Context): Boolean =
    sym.name.toString == "apply" &&
      sym.owner.fullName.toString.stripSuffix("$") == "scala.Some"

  private def isScalaOptionApply(sym: Symbol)(using Context): Boolean =
    sym.name.toString == "apply" &&
      sym.owner.fullName.toString.stripSuffix("$") == "scala.Option"

  private def isScalaEitherApply(sym: Symbol)(using Context): Boolean = {
    if sym == NoSymbol then false
    else {
      val ownerSym = sym.owner
      val owner =
        if ownerSym == NoSymbol then "" else ownerSym.fullName.toString.stripSuffix("$")
      sym.name.toString == "apply" &&
        (owner == "scala.util.Left" || owner == "scala.util.Right")
    }
  }

  private def isScalaTupleApply(sym: Symbol, argCount: Int)(using Context): Boolean =
    sym.name.toString == "apply" &&
      argCount >= 2 &&
      argCount <= 22 &&
      sym.owner.fullName.toString.stripSuffix("$") == s"scala.Tuple$argCount"

  private def isScalaRuntimeArraysNewArray(sym: Symbol)(using Context): Boolean =
    sym == defn.newArrayMethod ||
      (sym.name.toString == "newArray" &&
        sym.owner.fullName.toString.stripSuffix("$") == "scala.runtime.Arrays")

  private def isRiftRegionListPrepend(sym: Symbol)(using Context): Boolean = {
    val name = sym.name.toString
    name == "prependRegionList" && isRiftRegionCompanionOwner(sym)
  }

  private def isRiftCheckedBufferAppend(sym: Symbol)(using Context): Boolean = {
    val name = sym.name.toString
    name == "append" ||
      name == "appendToObjectBuffer" ||
      name == "appendToRegionBuffer"
  }

  private def isRiftCheckedBufferGet(sym: Symbol)(using Context): Boolean = {
    val name = sym.name.toString
    name == "get" ||
      name == "getFromObjectBuffer" ||
      name == "getFromRegionBuffer"
  }

  private def isRiftCheckedOwnerContainerType(tpe: Type)(using Context): Boolean = {
    val objectBufferName =
      "scala.scalanative.memory.RiftRegion.ObjectBuffer"
    val regionBufferName =
      "scala.scalanative.memory.RiftRegion.RegionBuffer"
    val widened = tpe.widenDealias
    tpe.show.contains(objectBufferName) ||
      tpe.show.contains(regionBufferName) ||
      widened.show.contains(objectBufferName) ||
      widened.show.contains(regionBufferName)
  }

  private def uncapturedType(tpe: Type)(using Context): Type =
    try
      CapturingType
        .decomposeCapturingType(tpe)
        .map((parent, _) => parent)
        .getOrElse(tpe)
    catch {
      case _: Throwable => tpe
    }

  private def typeCandidates(tpe: Type)(using Context): List[Type] =
    List(tpe, tpe.widenDealias, uncapturedType(tpe), uncapturedType(tpe.widenDealias))

  private def checkedOwnerContainerValueType(
      tpe: Type
  )(using Context): Option[Type] =
    typeCandidates(tpe).collectFirst {
      case candidate @ AppliedType(_, value :: Nil)
          if isRiftCheckedOwnerContainerType(candidate) =>
        value
    }

  private def checkedBufferGetOwnerAndValueType(
      app: Apply,
      sym: Symbol
  )(using Context): Option[(Symbol, Type)] =
    if !isRiftCheckedBufferGet(sym) then None
    else {
      val contextTrees = collectTrees(app.fun) ::: app.args
      val ownerFromContext =
        contextTrees
          .find(tree => isRiftInferredAllocationOwnerType(tree.tpe))
          .map(_.symbol)
          .filter(_ != NoSymbol)
      val ownerFromResult =
        captureOwnerSymbols(app.tpe, ctx.owner)
          .distinct
          .filter(isRiftInferredAllocationOwnerSymbol)
          .headOption
      val valueType =
        contextTrees
          .find(tree => isRiftCheckedOwnerContainerType(tree.tpe))
          .flatMap(tree =>
            checkedOwnerContainerValueType(tree.tpe)
              .orElse(
                tree
                  .getAttachment(NirDefinitions.NonErasedType)
                  .flatMap(checkedOwnerContainerValueType)
              )
          )
          .orElse(Some(app.tpe))
      for
        ownerSym <- ownerFromContext.orElse(ownerFromResult)
        value <- valueType
      yield ownerSym -> value
    }

  private def isRiftCheckedPriorityQueueType(tpe: Type)(using Context): Boolean = {
    val priorityQueueName =
      "scala.scalanative.memory.RiftRegion.RegionPriorityQueue"
    val indexedPriorityQueueName =
      "scala.scalanative.memory.RiftRegion.RegionIndexedPriorityQueue"
    val longIndexedPriorityQueueName =
      "scala.scalanative.memory.RiftRegion.RegionLongIndexedPriorityQueue"
    val widened = tpe.widenDealias
    List(tpe.show, widened.show).exists { text =>
      text.contains(priorityQueueName) ||
      text.contains(indexedPriorityQueueName) ||
        text.contains(longIndexedPriorityQueueName)
    }
  }

  private def isRiftCheckedPriorityQueueResult(sym: Symbol)(using
      Context
  ): Boolean = {
    val name = sym.name.toString
    name == "peek" ||
      name == "pop" ||
      name == "get" ||
      name == "peekFromRegionPriorityQueue" ||
      name == "popFromRegionPriorityQueue" ||
      name == "peekFromRegionIndexedPriorityQueue" ||
      name == "popFromRegionIndexedPriorityQueue" ||
      name == "getFromRegionIndexedPriorityQueue" ||
      name == "peekFromRegionLongIndexedPriorityQueue" ||
      name == "popFromRegionLongIndexedPriorityQueue" ||
      name == "getFromRegionLongIndexedPriorityQueue"
  }

  private def checkedPriorityQueueValueType(
      tpe: Type
  )(using Context): Option[Type] =
    typeCandidates(tpe).collectFirst {
      case candidate @ AppliedType(_, value :: Nil)
          if isRiftCheckedPriorityQueueType(candidate) =>
        value
    }

  private def checkedPriorityQueueResultOwnerAndValueType(
      app: Apply,
      sym: Symbol
  )(using Context): Option[(Symbol, Type)] =
    if !isRiftCheckedPriorityQueueResult(sym) then None
    else {
      val contextTrees = collectTrees(app.fun) ::: app.args
      val hasCheckedQueue =
        contextTrees.exists(tree => isRiftCheckedPriorityQueueType(tree.tpe))
      if !hasCheckedQueue then None
      else {
        val ownerFromContext =
          contextTrees
            .find(tree => isRiftInferredAllocationOwnerType(tree.tpe))
            .map(_.symbol)
            .filter(_ != NoSymbol)
        val ownerFromResult =
          captureOwnerSymbols(app.tpe, ctx.owner)
            .distinct
            .filter(isRiftInferredAllocationOwnerSymbol)
            .headOption
        val valueType =
          contextTrees
            .find(tree => isRiftCheckedPriorityQueueType(tree.tpe))
            .flatMap(tree =>
              checkedPriorityQueueValueType(tree.tpe)
                .orElse(
                  tree
                    .getAttachment(NirDefinitions.NonErasedType)
                    .flatMap(checkedPriorityQueueValueType)
                )
            )
            .orElse(Some(app.tpe))
        for
          ownerSym <- ownerFromContext.orElse(ownerFromResult)
          value <- valueType
        yield ownerSym -> value
      }
    }

  private def isRiftCheckedStreamRankType(tpe: Type)(using Context): Boolean = {
    val indexedRankName =
      "scala.scalanative.memory.RiftRegion.StreamWindowIndexedRank"
    val longIndexedRankName =
      "scala.scalanative.memory.RiftRegion.StreamWindowLongIndexedRank"
    val tableRankName =
      "scala.scalanative.memory.RiftRegion.StreamWindowTableRank"
    val widened = tpe.widenDealias
    List(tpe.show, widened.show).exists { text =>
      text.contains(indexedRankName) ||
        text.contains(longIndexedRankName) ||
        text.contains(tableRankName)
    }
  }

  private def isRiftCheckedStreamRankResult(sym: Symbol)(using
      Context
  ): Boolean = {
    val name = sym.name.toString
    name == "peekWindowRank" ||
      name.contains("peekWindowRank") ||
      name == "peekTableRank" ||
      name.contains("peekTableRank") ||
      name == "getWindowRank" ||
      name.contains("getWindowRank") ||
      name == "getTableRank" ||
      name.contains("getTableRank")
  }

  private def checkedStreamRankValueType(
      tpe: Type
  )(using Context): Option[Type] =
    typeCandidates(tpe).collectFirst {
      case candidate @ AppliedType(_, value :: Nil)
          if isRiftCheckedStreamRankType(candidate) =>
        value
    }

  private def checkedStreamRankResultOwnerAndValueType(
      app: Apply,
      sym: Symbol
  )(using Context): Option[(Symbol, Type)] =
    if !isRiftCheckedStreamRankResult(sym) then None
    else {
      val contextTrees = collectTrees(app.fun) ::: app.args
      val hasCheckedRank =
        contextTrees.exists(tree => isRiftCheckedStreamRankType(tree.tpe))
      if !hasCheckedRank then None
      else {
        val ownerFromContext =
          contextTrees
            .find(tree =>
              tree.symbol != NoSymbol &&
                isRiftFrameworkOwnerTokenSymbol(tree.symbol)
            )
            .map(_.symbol)
        val ownerFromResult =
          captureOwnerSymbols(app.tpe, ctx.owner)
            .distinct
            .filter(isRiftFrameworkOwnerTokenSymbol)
            .headOption
        val valueType =
          contextTrees
            .find(tree => isRiftCheckedStreamRankType(tree.tpe))
            .flatMap(tree =>
              tree
                .getAttachment(NirDefinitions.NonErasedType)
                .flatMap(checkedStreamRankValueType)
                .orElse(checkedStreamRankValueType(tree.tpe))
            )
            .orElse(Some(app.tpe))
        for
          ownerSym <- ownerFromContext.orElse(ownerFromResult)
          value <- valueType
        yield ownerSym -> value
      }
    }

  private def isRiftChildRegionFactory(sym: Symbol)(using Context): Boolean = {
    val name = sym.name.toString
    isRiftRegionCompanionOwner(sym) && (
      name == "childRegion" ||
        name == "childBucketRegion" ||
        name == "streamBucketRegion" ||
        name == "pageTokenAppendRegionFor" ||
        name == "pageTokenAppendOpenRegionFor" ||
        name == "pageTokenAppendRiftOpenHandleFor" ||
        name == "pageTokenMapFilterRegionFor" ||
        name == "pageTokenMapFilterOpenRegionFor" ||
        name == "pageTokenCountByKeyRegionFor" ||
        name == "pageTokenCountByKeyOpenRegionFor" ||
        name == "epochBufferRegionFor" ||
        name == "epochBufferOpenRegionFor" ||
        name == "epochFoldRegionFor" ||
        name == "transactionRegionFor" ||
        name == "chunkAppendRegionFor"
    )
  }

  private def childRegionFactoryApply(tree: Tree)(using Context): Option[Apply] =
    tree match {
      case app: Apply if isRiftChildRegionFactory(calledSymbol(app)) =>
        Some(app)
      case Typed(expr, _)      => childRegionFactoryApply(expr)
      case Inlined(_, _, expr) => childRegionFactoryApply(expr)
      case Block(_, expr)      => childRegionFactoryApply(expr)
      case _                   => None
    }

  private def collectTrees(tree: Tree): List[Tree] =
    tree match {
      case app @ Apply(fun, args) =>
        app :: collectTrees(fun) ::: args.flatMap(collectTrees)
      case tpe @ TypeApply(fun, args) =>
        tpe :: collectTrees(fun) ::: args.flatMap(collectTrees)
      case sel @ Select(qualifier, _) =>
        sel :: collectTrees(qualifier)
      case typed @ Typed(expr, _) =>
        typed :: collectTrees(expr)
      case inlined @ Inlined(_, _, expr) =>
        inlined :: collectTrees(expr)
      case block @ Block(Nil, expr) =>
        block :: collectTrees(expr)
      case other =>
        other :: Nil
    }

  private def checkedBufferAppendOwnerAndValue(
      app: Apply,
      sym: Symbol
  )(using Context): Option[(Tree, Tree)] =
    if !isRiftCheckedBufferAppend(sym) || app.args.isEmpty then None
    else {
      val value = app.args.last
      val contextTrees = collectTrees(app.fun) ::: app.args.dropRight(1)
      val hasCheckedBuffer =
        contextTrees.exists(tree => isRiftCheckedOwnerContainerType(tree.tpe))
      if !hasCheckedBuffer then None
      else
        contextTrees
            .find(tree => isRiftInferredAllocationOwnerType(tree.tpe))
            .map(owner => (owner, value))
    }

  private def checkedPriorityQueueOwnerAndValues(
      app: Apply,
      sym: Symbol
  )(using Context): Option[(Tree, List[Tree])] = {
    val name = sym.name.toString
    if name != "push" && name != "put" &&
      !name.contains("RegionPriorityQueue")
    then None
    else {
      val contextTrees = collectTrees(app.fun) ::: app.args
      val hasCheckedQueue =
        contextTrees.exists(tree => isRiftCheckedPriorityQueueType(tree.tpe))
      if !hasCheckedQueue then None
      else
        contextTrees
          .find(tree => isRiftInferredAllocationOwnerType(tree.tpe))
          .map(owner => (owner, app.args))
    }
  }

  private def checkedStreamRankOwnerAndValues(
      app: Apply,
      sym: Symbol
  )(using Context): Option[(Tree, List[Tree])] = {
    val name = sym.name.toString
    if name != "putWindowRank" &&
      name != "putWindowRankInBucket" &&
      name != "putTableRankInBucket"
    then None
    else {
      val contextTrees = collectTrees(app.fun) ::: app.args
      val hasCheckedRank =
        contextTrees.exists(tree => isRiftCheckedStreamRankType(tree.tpe))
      if !hasCheckedRank then None
      else
        contextTrees
          .find(tree =>
            tree.symbol != NoSymbol &&
              isRiftFrameworkOwnerTokenSymbol(tree.symbol)
          )
          .map(owner => (owner, app.args))
    }
  }
  private def capturedArrayElementOwnersFromTypes(
      arrayTypes: List[Type],
      allowFrameworkOwnerToken: Boolean = false
  )(using Context): List[Symbol] = {
    def elementType(tpe: Type): Option[Type] =
      uncapturedType(tpe.widenDealias) match {
        case AppliedType(_, elem :: Nil) => Some(elem)
        case _                           => None
      }

    arrayTypes
      .flatMap(tpe => typeCandidates(tpe).flatMap(elementType))
      .filter(typeMentionsRiftCapture)
      .flatMap(elem =>
        captureOwnerSymbols(elem, ctx.owner)
          .distinct
          .filter(owner =>
            isRiftInferredAllocationOwnerSymbol(owner) ||
              (allowFrameworkOwnerToken &&
                isRiftFrameworkOwnerTokenSymbol(owner))
          )
      )
      .distinct
  }

  private def capturedArrayElementOwnerNamesFromTypes(
      arrayTypes: List[Type]
  )(using Context): List[String] = {
    def elementType(tpe: Type): Option[Type] =
      uncapturedType(tpe.widenDealias) match {
        case AppliedType(_, elem :: Nil) => Some(elem)
        case _                           => None
      }

    arrayTypes
      .flatMap(tpe => typeCandidates(tpe).flatMap(elementType))
      .filter(typeMentionsRiftCapture)
      .flatMap(captureOwnerNames)
      .distinct
  }

  private def capturedArrayElementOwners(
      array: Tree
  )(using Context): List[Symbol] = {
    val arrayTypes =
      array.tpe :: array
        .getAttachment(NirDefinitions.NonErasedType)
        .toList :::
        Option
          .when(array.symbol != NoSymbol)(array.symbol.info)
          .toList

    val ownersFromType = capturedArrayElementOwnersFromTypes(arrayTypes)

    val ownersFromLocal =
      directLocalIdent(array)
        .flatMap(localArrayElementOwnerSyms.get)
        .toList

    (ownersFromType ++ ownersFromLocal).map(canonicalOwner).distinct
  }

  private def checkedStreamRankLocalSymbols(app: Apply)(using
      Context
  ): List[Symbol] =
    (collectTrees(app.fun) ::: app.args)
      .filter(tree => isRiftCheckedStreamRankType(tree.tpe))
      .flatMap(directLocalIdent)
      .filter(sym => sym != NoSymbol && !sym.is(Mutable))
      .distinct

  private def directArrayElementOwners(
      value: Tree,
      allowFrameworkOwnerToken: Boolean,
      fallbackOwner: Option[Symbol] = None
  )(using Context): List[Symbol] =
    directArrayApply(value).toList.flatMap { array =>
      val arrayTypes =
        array.tpe :: array
          .getAttachment(NirDefinitions.NonErasedType)
          .toList
      val structuredOwners =
        capturedArrayElementOwnersFromTypes(
          arrayTypes,
          allowFrameworkOwnerToken = allowFrameworkOwnerToken
        )
      val ownerNames = capturedArrayElementOwnerNamesFromTypes(arrayTypes)
      structuredOwners ++ fallbackOwner
        .map(canonicalOwner)
        .filter(owner => ownerNames.contains(owner.name.toString))
        .toList
    }.map(canonicalOwner).distinct

  private def rememberCheckedStreamRankArrayElementOwner(
      app: Apply,
      ownerTree: Tree,
      values: List[Tree]
  )(using Context): Unit = {
    val rankSyms = checkedStreamRankLocalSymbols(app)
    if rankSyms.nonEmpty then {
      val fallbackOwner =
        Option(ownerTree.symbol)
          .filter(sym => sym != NoSymbol)
          .map(canonicalOwner)
      val rankValueTypes =
        (collectTrees(app.fun) ::: app.args)
          .filter(tree => isRiftCheckedStreamRankType(tree.tpe))
          .flatMap(tree =>
            tree
              .getAttachment(NirDefinitions.NonErasedType)
              .flatMap(checkedStreamRankValueType)
              .orElse(checkedStreamRankValueType(tree.tpe))
          )
          .distinct
      val rankValueElementOwners =
        capturedArrayElementOwnersFromTypes(
          rankValueTypes,
          allowFrameworkOwnerToken = true
        ).map(canonicalOwner).distinct
      val rankTypeOwners =
        rankValueElementOwners match {
          case owner :: Nil if fallbackOwner.contains(owner) =>
            owner :: Nil
          case Nil =>
            val rankValueElementOwnerNames =
              capturedArrayElementOwnerNamesFromTypes(rankValueTypes)
            fallbackOwner
              .filter(owner =>
                rankValueElementOwnerNames.contains(owner.name.toString)
              )
              .toList
          case _ => Nil
        }
      val owners =
        (values.flatMap(value =>
          directArrayElementOwners(
            value,
            allowFrameworkOwnerToken = true,
            fallbackOwner = fallbackOwner
          )
        ) ++ rankTypeOwners).distinct
      owners match {
        case owner :: Nil =>
          rankSyms.foreach { rankSym =>
            localStreamRankArrayElementOwnerSyms.update(rankSym, owner)
          }
        case _ => ()
      }
    }
  }

  private def checkedStreamRankResultArrayElementOwners(
      app: Apply
  )(using Context): List[Symbol] =
    checkedStreamRankLocalSymbols(app)
      .flatMap(localStreamRankArrayElementOwnerSyms.get)
      .map(canonicalOwner)
      .distinct

  private def markArrayElementStoreConstrainedAllocation(
      array: Tree,
      value: Tree
  )(using Context): Unit = {
    val owners = capturedArrayElementOwners(array)
    owners match {
      case owner :: Nil =>
        directLocalIdent(value).foreach { target =>
          val allocationTargets = localAllocationTargets(target).distinct
          markSelectedAliasOwner(
            target,
            allocationTargets,
            owner,
            "selected local allocation alias is constrained by checked region array element owner",
            value.srcPos
          )
          allocationTargets.foreach { allocationTarget =>
            markRegionOwner(
              allocationTarget,
              owner,
              "local direct new or selected allocation is constrained by checked region array element owner",
              value.srcPos
            )
          }
        }
        directReturnedNewApplies(value).foreach { app =>
          markDirectConstructOwner(
            app,
            owner,
            "direct new or branch/match returned array store value is constrained by checked region array element owner"
          )
        }
        markNestedClosureOwnerConstrainedAllocations(
          owner,
          value,
          "nested direct closure array store value is constrained by checked region array element owner",
          "nested local closure array store value is constrained by checked region array element owner",
          "nested closure array store body return is constrained by checked region array element owner",
          "conflicting inferred nested array-store local closure owners"
        )
        markClosureValueOwnerConstrainedAllocation(
          owner,
          value,
          "direct closure array store value is constrained by checked region array element owner",
          "local closure array store value is constrained by checked region array element owner",
          "closure array store body return is constrained by a checked region array element owner",
          "conflicting inferred array-store local closure owners"
        )
      case first :: second :: _ =>
        directLocalIdent(value).foreach { target =>
          val allocationTargets = localAllocationTargets(target).distinct
          val rejectedTargets =
            if allocationTargets.nonEmpty then target :: allocationTargets
            else target :: Nil
          rejectedTargets.distinct.foreach { rejectedTarget =>
            markRejected(
              rejectedTarget,
              s"ambiguous captured array element owners: ${first.name} and ${second.name}",
              value.srcPos
            )
          }
        }
        directReturnedNewApplies(value).foreach { app =>
          updateDecision(
            calledSymbol(app),
            RiftRegionInference.AllocationOwner.Rejected,
            s"ambiguous captured array element owners: ${first.name} and ${second.name}",
            app.srcPos
          )
        }
        directReturnedClosures(value).foreach { closure =>
          val closureSym = calledSymbol(closure)
          if closureSym != NoSymbol then
            updateDecision(
              closureSym,
              RiftRegionInference.AllocationOwner.Rejected,
              s"ambiguous captured array element owners: ${first.name} and ${second.name}",
              closure.srcPos
            )
        }
        returnedLocalIdents(value)
          .flatMap(localClosureAllocationPairs)
          .map(_._1)
          .distinct
          .foreach { target =>
            markRejected(
              target,
              s"ambiguous captured array element owners: ${first.name} and ${second.name}",
              value.srcPos
            )
          }
      case Nil => ()
    }
  }

  private def currentScopeOwnerSymbols(using Context): Set[Symbol] =
    ownerChain(ctx.owner).toSet

  private def capturedCurrentScopeOwners(
      expected: Type
  )(using Context): List[Symbol] =
    captureOwnerSymbols(expected, ctx.owner)
      .map(canonicalOwner)
      .distinct
      .filter(isRiftInferredAllocationOwnerSymbol)
      .filter(owner => currentScopeOwnerSymbols.contains(owner.owner))

  private def appliedArguments(tree: Tree): List[Tree] =
    tree match {
      case Apply(fun, args)     => appliedArguments(fun) ::: args
      case TypeApply(fun, _)    => appliedArguments(fun)
      case Typed(expr, _)       => appliedArguments(expr)
      case Inlined(_, _, expr)  => appliedArguments(expr)
      case _                    => Nil
    }

  private def fullMethodArgumentPairs(
      app: Apply,
      sym: Symbol
  )(using Context): List[(Symbol, Tree)] = {
    val params = sym.paramSymss.flatten.filterNot(_.isType)
    val args = appliedArguments(app)
    if params.nonEmpty && args.length >= params.length then
      params.zip(args).take(params.length)
    else Nil
  }

  private def methodOwnerSubstitutions(
      pairs: List[(Symbol, Tree)]
  )(using Context): Map[Symbol, Symbol] =
    pairs.flatMap { (param, value) =>
      if isRiftInferredAllocationOwnerType(param.info) then
        directLocalIdent(value)
          .map(canonicalOwner)
          .filter(isRiftInferredAllocationOwnerSymbol)
          .map(owner => canonicalOwner(param) -> owner)
      else None
    }.toMap

  private def capturedMethodArgumentOwners(
      expected: Type,
      callee: Symbol,
      calleeOwnerSubstitutions: Map[Symbol, Symbol]
  )(using Context): List[Symbol] = {
    val localOwners = capturedCurrentScopeOwners(expected)
    val substitutedOwners =
      captureOwnerSymbols(expected, callee)
        .map(canonicalOwner)
        .flatMap(calleeOwnerSubstitutions.get)
    (localOwners ::: substitutedOwners)
      .map(canonicalOwner)
      .distinct
      .filter(isRiftInferredAllocationOwnerSymbol)
  }

  private def markMethodArgumentExpectedTypeConstrainedAllocation(
      expected: Type,
      value: Tree,
      callee: Symbol,
      calleeOwnerSubstitutions: Map[Symbol, Symbol]
  )(using Context): Unit =
    val expectedTypes =
      List(expected, value.tpe).filter(typeMentionsRiftCapture)
    if expectedTypes.nonEmpty &&
      !isRiftRegionRuntimeAllocatorForwarderArg(callee, value)
    then {
      val owners =
        expectedTypes
          .flatMap(expected =>
            capturedMethodArgumentOwners(
              expected,
              callee,
              calleeOwnerSubstitutions
            )
          )
          .map(canonicalOwner)
          .distinct
      owners match {
        case owner :: Nil =>
          val localTarget = directLocalIdent(value)
          val localAllocTargets =
            localTarget.toList.flatMap(localAllocationTargets).distinct
          val directConstructs = directReturnedNewApplies(value)
          val directClosureArgs = directReturnedClosures(value)
          val localClosureArgs =
            returnedLocalIdents(value)
              .flatMap(localClosureAllocationPairs)
              .distinct
          localTarget.foreach { target =>
            if localAllocTargets.nonEmpty then
              markSelectedAliasOwner(
                target,
                localAllocTargets,
                owner,
                "selected local allocation alias flows into a method argument captured by a checked region",
                value.srcPos
              )
              localAllocTargets.foreach { allocationTarget =>
                markRegionOwner(
                  allocationTarget,
                  owner,
                  "local direct new or selected allocation flows into a method argument captured by a checked region",
                  value.srcPos
                )
              }
            else if
              localClosureArgs.isEmpty &&
              !RiftRegionInference.inferredAllocationOwners
                .get(target)
                .exists(canonicalOwner(_) == owner) &&
              !typeMentionsRiftCapture(value.tpe)
            then
              report.error(
                "Rift checked region method argument cannot pass an unrooted heap object; use RiftRegion.root(value) for heap metadata.",
                value.srcPos
              )
          }
          directConstructs.foreach { app =>
            markDirectConstructOwner(
              app,
              owner,
              "direct new method argument is captured by a checked region"
            )
          }
          markNestedClosureOwnerConstrainedAllocations(
            owner,
            value,
            "nested direct closure method argument is captured by a checked region",
            "nested local closure method argument is captured by a checked region",
            "nested closure method argument body return is constrained by a captured checked region owner",
            "conflicting inferred nested method-argument local closure owners"
          )
          directClosureArgs.foreach { closure =>
            closure.putAttachment(
              NirDefinitions.InferredRiftAllocationOwner,
              owner
            )
            markClosureBodyOwner(
              closure,
              owner,
              "closure method argument body return is constrained by a captured checked region owner"
            )
            // Also mark allocation effect for effect-polymorphic closures.
            // When a closure with an allocation effect is passed to a method
            // with an owner-token parameter, the effect is instantiated with
            // the actual owner from the call site.
            markClosureAllocationEffect(
              closure,
              owner,
              "closure method argument allocation effect is instantiated by checked region owner"
            )
            val closureSym = calledSymbol(closure)
            if closureSym != NoSymbol then
              updateDecision(
                closureSym,
                RiftRegionInference.AllocationOwner.Region(owner),
                "closure method argument is captured by a checked region",
                closure.srcPos
              )
          }
          localClosureArgs.foreach { (target, closure) =>
            val canonical = canonicalOwner(owner)
            val existingOwner =
              RiftRegionInference.inferredAllocationOwners
                .get(target)
                .map(canonicalOwner)
            existingOwner match {
              case Some(previous) if previous != canonical =>
                markRejected(
                  target,
                  s"conflicting inferred local closure argument owners: ${previous.name} and ${canonical.name}",
                  value.srcPos
                )
              case _ =>
                closure.putAttachment(
                  NirDefinitions.InferredRiftAllocationOwner,
                  canonical
                )
                markRegionOwner(
                  target,
                  canonical,
                  "local closure flows into a method argument captured by a checked region",
                  value.srcPos
                )
                markClosureBodyOwner(
                  closure,
                  canonical,
                  "local closure method argument body return is constrained by a captured checked region owner"
                )
            }
          }
          if localTarget.isEmpty &&
            directConstructs.isEmpty &&
            directClosureArgs.isEmpty &&
            localClosureArgs.isEmpty &&
            directReturnedInferredMethodCalls(value).isEmpty &&
            !typeMentionsRiftCapture(value.tpe)
          then
            report.error(
              "Rift checked region method argument cannot pass an unrooted heap object; use RiftRegion.root(value) for heap metadata.",
              value.srcPos
            )
        case first :: second :: _ =>
          directLocalIdent(value).foreach { target =>
            val allocationTargets = localAllocationTargets(target).distinct
            if allocationTargets.nonEmpty then
              (target :: allocationTargets).distinct.foreach { allocationTarget =>
                markRejected(
                  allocationTarget,
                  s"ambiguous captured method argument owners: ${first.name} and ${second.name}",
                  value.srcPos
                )
              }
            else
              markRejected(
                target,
                s"ambiguous captured method argument owners: ${first.name} and ${second.name}",
                value.srcPos
              )
          }
          directReturnedNewApplies(value).foreach { app =>
            updateDecision(
              calledSymbol(app),
              RiftRegionInference.AllocationOwner.Rejected,
              s"ambiguous captured method argument owners: ${first.name} and ${second.name}",
              app.srcPos
            )
          }
          directClosure(value).foreach { closure =>
            val closureSym = calledSymbol(closure)
            if closureSym != NoSymbol then
              updateDecision(
                closureSym,
                RiftRegionInference.AllocationOwner.Rejected,
                s"ambiguous captured method argument owners: ${first.name} and ${second.name}",
                closure.srcPos
              )
          }
        case Nil => ()
      }
    }

  private def directLocalIdent(tree: Tree)(using Context): Option[Symbol] =
    tree match {
      case id: Ident                                      => Some(id.symbol)
      case Typed(expr, _)                                => directLocalIdent(expr)
      case Inlined(_, _, expr)                           => directLocalIdent(expr)
      case Block(Nil, expr)                              => directLocalIdent(expr)
      case TypeApply(Select(expr, nme.asInstanceOf_), _) => directLocalIdent(expr)
      case _                                             => None
    }

  private def returnedLocalIdents(tree: Tree)(using Context): List[Symbol] =
    tree match {
      case id: Ident =>
        id.symbol :: Nil
      case Return(expr, _) =>
        returnedLocalIdents(expr)
      case Labeled(_, body) =>
        returnedLocalIdents(body)
      case Typed(expr, _) =>
        returnedLocalIdents(expr)
      case Inlined(_, _, expr) =>
        returnedLocalIdents(expr)
      case Block(_, expr) =>
        returnedLocalIdents(expr)
      case TypeApply(Select(expr, nme.asInstanceOf_), _) =>
        returnedLocalIdents(expr)
      case If(_, thenp, elsep) =>
        returnedLocalIdents(thenp) ::: returnedLocalIdents(elsep)
      case Match(_, cases) =>
        cases.flatMap { case CaseDef(_, _, body) =>
          returnedLocalIdents(body)
        }
      case _ =>
        Nil
    }

  private def markLocalOwnerConstrainedAllocation(
      ownerTree: Tree,
      valueTree: Tree,
      reason: String,
      allowFrameworkOwnerToken: Boolean = false
  )(using Context): Unit =
    val ownerIsValid =
      ownerTree.symbol != NoSymbol &&
        (isRiftInferredAllocationOwnerSymbol(ownerTree.symbol) ||
          (allowFrameworkOwnerToken &&
            isRiftFrameworkOwnerTokenSymbol(ownerTree.symbol)))
    if ownerIsValid
    then {
      directLocalIdent(valueTree).foreach { target =>
        markLocalAllocationTargets(
          target,
          ownerTree.symbol,
          reason,
          "selected local allocation alias is constrained by a checked region owner token",
          valueTree.srcPos
        )
      }
      markNestedLocalOwnerConstrainedAllocations(
        ownerTree.symbol,
        valueTree,
        reason,
        "nested local allocation alias is constrained by a checked region owner token"
      )
      markNestedClosureOwnerConstrainedAllocations(
        ownerTree.symbol,
        valueTree,
        "nested direct closure value is constrained by checked region owner token",
        "nested local closure value is constrained by checked region owner token",
        "nested closure body return is constrained by checked region owner token",
        "conflicting inferred nested owner-token local closure owners"
      )
      directReturnedNewApplies(valueTree).foreach { app =>
        markDirectConstructOwner(app, ownerTree.symbol, reason)
      }
      markClosureValueOwnerConstrainedAllocation(
        ownerTree.symbol,
        valueTree,
        "direct closure value is constrained by checked region owner token",
        "local closure value is constrained by checked region owner token",
        "closure body return is constrained by a checked region owner token",
        "conflicting inferred owner-token local closure owners"
      )
    }

  private def markSelectedLocalOwnerConstrainedAllocation(
      ownerTree: Tree,
      valueTree: Tree,
      reason: String,
      allowFrameworkOwnerToken: Boolean = false
  )(using Context): Unit = {
    val ownerIsValid =
      ownerTree.symbol != NoSymbol &&
        (isRiftInferredAllocationOwnerSymbol(ownerTree.symbol) ||
          (allowFrameworkOwnerToken &&
            isRiftFrameworkOwnerTokenSymbol(ownerTree.symbol)))
    if ownerIsValid then
      directLocalIdent(valueTree).foreach { target =>
        val allocationTargets = localAllocationTargets(target).distinct
        if allocationTargets.nonEmpty && !allocationTargets.contains(target) then
          markSelectedAliasOwner(
            target,
            allocationTargets,
            ownerTree.symbol,
            "selected local allocation alias is constrained by a checked region owner token",
            valueTree.srcPos
          )
          allocationTargets.foreach { allocationTarget =>
            markRegionOwner(
              allocationTarget,
              ownerTree.symbol,
              reason,
              valueTree.srcPos
            )
          }
      }
      if directLocalIdent(valueTree).isEmpty then
        returnedLocalIdents(valueTree).distinct.foreach { target =>
          val allocationTargets = localAllocationTargets(target).distinct
          if allocationTargets.nonEmpty then {
            markSelectedAliasOwner(
              target,
              allocationTargets,
              ownerTree.symbol,
              "branch/match local allocation alias is constrained by a checked region owner token",
              valueTree.srcPos
            )
            allocationTargets.foreach { allocationTarget =>
              markRegionOwner(
                allocationTarget,
                ownerTree.symbol,
                reason,
                valueTree.srcPos
              )
            }
          }
        }
      directReturnedNewApplies(valueTree).foreach { app =>
        markDirectConstructOwner(app, ownerTree.symbol, reason)
      }
      markNestedClosureOwnerConstrainedAllocations(
        ownerTree.symbol,
        valueTree,
        "nested direct closure value is constrained by checked framework owner token",
        "nested local closure value is constrained by checked framework owner token",
        "nested closure body return is constrained by checked framework owner token",
        "conflicting inferred nested framework-owner-token local closure owners"
      )
      markClosureValueOwnerConstrainedAllocation(
        ownerTree.symbol,
        valueTree,
        "direct closure value is constrained by checked framework owner token",
        "local closure value is constrained by checked framework owner token",
        "closure body return is constrained by a checked framework owner token",
        "conflicting inferred framework-owner-token local closure owners"
      )
  }

  private def markLocalExpectedTypeConstrainedAllocation(
      expected: Type,
      valueTree: Tree,
      owner: Symbol,
      reason: String
  )(using Context): Unit =
    directLocalIdent(valueTree).foreach { target =>
      val allocationTargets = localAllocationTargets(target).distinct
      if allocationTargets.nonEmpty then {
        val owners =
          captureOwnerSymbols(expected, owner)
            .distinct
            .filter(isRiftInferredAllocationOwnerSymbol)
        owners match {
          case inferredOwner :: Nil =>
            markSelectedAliasOwner(
              target,
              allocationTargets,
              inferredOwner,
              "selected local allocation alias flows into a captured expected type",
              valueTree.srcPos
            )
            allocationTargets.foreach { allocationTarget =>
              markRegionOwner(
                allocationTarget,
                inferredOwner,
                reason,
                valueTree.srcPos
              )
            }
          case first :: second :: _ =>
            val rejectedTargets =
              if allocationTargets.nonEmpty then target :: allocationTargets
              else target :: Nil
            rejectedTargets.distinct.foreach { allocationTarget =>
              markRejected(
                allocationTarget,
                s"ambiguous captured expected type owners: ${first.name} and ${second.name}",
                valueTree.srcPos
              )
            }
          case Nil => ()
        }
      }
    }

  private def closureCapturesOwner(
      closure: Closure,
      owner: Symbol
  )(using Context): Boolean = {
    val canonical = canonicalOwner(owner)
    val ownerInClosureType =
      captureOwnerSymbols(closure.tpe, ctx.owner)
        .map(canonicalOwner)
        .contains(canonical)
    val ownerInClosureTypeName =
      val ownerNames = captureOwnerNames(closure.tpe).toSet
      ownerNames.contains(canonical.name.toString) ||
        localOwnerAliases.exists { (alias, target) =>
          canonicalOwner(target) == canonical &&
            ownerNames.contains(alias.name.toString)
        }
    ownerInClosureType || ownerInClosureTypeName || closure.env.exists { env =>
      val envOwner = canonicalOwner(env.symbol)
      envOwner == canonical ||
      localOwnerAliases
        .get(env.symbol)
        .map(canonicalOwner)
        .contains(canonical) ||
        isRiftInferredAllocationOwnerType(env.tpe) ||
      (
        env.symbol != NoSymbol &&
          isRiftInferredAllocationOwnerType(env.symbol.info)
      )
    }
  }

  private def markClosureBodyOwner(
      closure: Closure,
      owner: Symbol,
      reason: String
  )(using Context): Unit = {
    val Closure(_, fun, _) = closure: @unchecked
    val funSym = fun.symbol
    if funSym != NoSymbol && closureCapturesOwner(closure, owner) then {
      val canonical = canonicalOwner(owner)
        RiftRegionInference.inferredClosureBodyOwners.update(funSym, canonical)
        updateDecision(
          funSym,
          RiftRegionInference.AllocationOwner.Region(canonical),
          reason,
          closure.srcPos
        )
    }
  }

  // Mark allocation effect for a closure whose expected type has a captured
  // owner, even if the closure doesn't explicitly capture the owner.
  // This is the ReML-style effect polymorphism mechanism: the closure type
  // carries the allocation effect, and the caller will inject the owner
  // handle at the call site.
  private def markClosureAllocationEffect(
      closure: Closure,
      owner: Symbol,
      reason: String
  )(using Context): Unit = {
    val Closure(_, fun, _) = closure: @unchecked
    val funSym = fun.symbol
    val canonical = canonicalOwner(owner)
    if funSym != NoSymbol then {
      // Record the allocation effect on the closure symbol
      RiftRegionInference.inferredClosureAllocationEffects.update(
        funSym,
        canonical
      )
      // Also mark the closure body owner so the inference phase knows
      // the closure body should be treated as region-allocated.
      RiftRegionInference.inferredClosureBodyOwners.update(funSym, canonical)
      // Also record by source span for lambda-lifted closures
      RiftRegionInference.sourceSpanKey(closure.srcPos).foreach { key =>
        RiftRegionInference.inferredClosureAllocationEffectsBySourceSpan
          .update(key, canonical)
      }
      updateDecision(
        funSym,
        RiftRegionInference.AllocationOwner.Region(canonical),
        reason,
        closure.srcPos
      )
    }
  }

  private def markDirectClosureRegionOwner(
      closure: Closure,
      owner: Symbol,
      reason: String,
      bodyReason: String
  )(using Context): Unit = {
    val canonical = canonicalOwner(owner)
    val Closure(_, fun, _) = closure: @unchecked
    val funSym = fun.symbol
    if funSym != NoSymbol then
      RiftRegionInference.inferredClosureValueOwners.update(funSym, canonical)
    closure.putAttachment(
      NirDefinitions.InferredRiftAllocationOwner,
      canonical
    )
    markClosureSourceOwner(closure, canonical)
    markClosureBodyOwner(closure, canonical, bodyReason)
    val closureSym = calledSymbol(closure)
    if closureSym != NoSymbol then
      updateDecision(
        closureSym,
        RiftRegionInference.AllocationOwner.Region(canonical),
        reason,
        closure.srcPos
      )
  }

  private def markLocalClosureRegionOwner(
      target: Symbol,
      closure: Closure,
      owner: Symbol,
      reason: String,
      bodyReason: String,
      conflictReason: String,
      pos: dotty.tools.dotc.util.SrcPos
  )(using Context): Unit = {
    val canonical = canonicalOwner(owner)
    val existingOwner =
      RiftRegionInference.inferredAllocationOwners
        .get(target)
        .map(canonicalOwner)
    existingOwner match {
      case Some(previous) if previous != canonical =>
        markRejected(
          target,
          s"$conflictReason: ${previous.name} and ${canonical.name}",
          pos
        )
      case _ =>
        val Closure(_, fun, _) = closure: @unchecked
        val funSym = fun.symbol
        if funSym != NoSymbol then
          RiftRegionInference.inferredClosureValueOwners.update(
            funSym,
            canonical
          )
        closure.putAttachment(
          NirDefinitions.InferredRiftAllocationOwner,
          canonical
        )
        markClosureSourceOwner(closure, canonical)
        markRegionOwner(target, canonical, reason, pos)
        markClosureBodyOwner(closure, canonical, bodyReason)
    }
  }

  private def markClosureValueOwnerConstrainedAllocation(
      owner: Symbol,
      valueTree: Tree,
      directReason: String,
      localReason: String,
      bodyReason: String,
      conflictReason: String
  )(using Context): Unit = {
    directReturnedClosures(valueTree).foreach { closure =>
      markDirectClosureRegionOwner(closure, owner, directReason, bodyReason)
      // Also mark allocation effect for effect-polymorphic closures
      markClosureAllocationEffect(
        closure,
        owner,
        "closure value allocation effect is constrained by checked region owner token"
      )
    }
    returnedLocalIdents(valueTree)
      .flatMap(localClosureAllocationPairs)
      .distinct
      .foreach { (target, closure) =>
        markLocalClosureRegionOwner(
          target,
          closure,
          owner,
          localReason,
          bodyReason,
          conflictReason,
          valueTree.srcPos
        )
        // Also mark allocation effect for local closures
        markClosureAllocationEffect(
          closure,
          owner,
          "local closure allocation effect is constrained by checked region owner token"
        )
      }
  }

  private def markNestedClosureOwnerConstrainedAllocations(
      owner: Symbol,
      allocations: List[NestedClosureAllocation],
      directReason: String,
      localReason: String,
      bodyReason: String,
      conflictReason: String,
      pos: dotty.tools.dotc.util.SrcPos
  )(using Context): Unit =
    allocations.distinct.foreach {
      case DirectNestedClosure(closure) =>
        markDirectClosureRegionOwner(closure, owner, directReason, bodyReason)
      case LocalNestedClosureAlias(target) =>
        markRegionOwner(target, owner, localReason, pos)
      case LocalNestedClosure(target, closure) =>
        markLocalClosureRegionOwner(
          target,
          closure,
          owner,
          localReason,
          bodyReason,
          conflictReason,
          pos
        )
    }

  private def markNestedClosureOwnerConstrainedAllocations(
      owner: Symbol,
      valueTree: Tree,
      directReason: String,
      localReason: String,
      bodyReason: String,
      conflictReason: String
  )(using Context): Unit =
    markNestedClosureOwnerConstrainedAllocations(
      owner,
      nestedClosureAllocations(valueTree),
      directReason,
      localReason,
      bodyReason,
      conflictReason,
      valueTree.srcPos
    )

  private def markNestedClosureAliasesInRegionConstruct(
      owner: Symbol,
      valueTree: Tree,
      aliasReason: String,
      localReason: String,
      bodyReason: String,
      conflictReason: String
  )(using Context): Unit = {
    val treeLocalClosurePairs = localClosureAllocationPairsInTree(valueTree)

    directReturnedNewApplies(valueTree).foreach { app =>
      app.args.foreach { arg =>
        returnedLocalIdents(arg).distinct.foreach { target =>
          val pairs = treeLocalClosurePairs.getOrElse(
            target,
            localClosureAllocationPairs(target)
          )
          if pairs.nonEmpty then {
            if !pairs.exists(_._1 == target) then
              markRegionOwner(target, owner, aliasReason, arg.srcPos)
            pairs.distinct.foreach { (closureTarget, closure) =>
              markLocalClosureRegionOwner(
                closureTarget,
                closure,
                owner,
                localReason,
                bodyReason,
                conflictReason,
                arg.srcPos
              )
            }
          }
        }
      }
    }
  }

  private def directLocalClosureValDefs(
      tree: Tree
  )(using Context): Map[Symbol, Closure] =
    tree match {
      case vd: ValDef if !vd.symbol.is(Mutable) =>
        directClosure(vd.rhs).map(vd.symbol -> _).toMap
      case Block(stats, expr) =>
        (stats.flatMap(stat => directLocalClosureValDefs(stat)) :::
          directLocalClosureValDefs(expr).toList).toMap
      case Typed(expr, _) =>
        directLocalClosureValDefs(expr)
      case Inlined(_, _, expr) =>
        directLocalClosureValDefs(expr)
      case TypeApply(Select(expr, nme.asInstanceOf_), _) =>
        directLocalClosureValDefs(expr)
      case If(_, thenp, elsep) =>
        directLocalClosureValDefs(thenp) ++ directLocalClosureValDefs(elsep)
      case Match(_, cases) =>
        cases.flatMap { case CaseDef(_, _, body) =>
          directLocalClosureValDefs(body)
        }.toMap
      case _ =>
        Map.empty
    }

  private def captureOwnerNames(tpe: Type)(using Context): List[String] = {
    val CapturePattern = """(?:\^|->)\{([^}]*)\}""".r
    List(tpe.show, tpe.widenDealias.show).flatMap { text =>
      CapturePattern
        .findAllMatchIn(text)
        .flatMap(_.group(1).split(","))
        .map(_.trim)
        .filter(_.nonEmpty)
    }.distinct
  }

  private def ownerChain(owner: Symbol)(using Context): List[Symbol] =
    if owner == NoSymbol then Nil else owner :: ownerChain(owner.denot.owner)

  private def captureOwnerSymbols(
      tpe: Type,
      owner: Symbol
  )(using Context): List[Symbol] = {
    def symbolsFrom(cs: CaptureSet): List[Symbol] =
      cs.elems.iterator.toList.map(_.pathOwner).filter(_ != NoSymbol)

    val ownerScope = ownerChain(owner).toSet
    val localFromNames =
      captureOwnerNames(tpe).flatMap { name =>
        localCaptureOwnerSymsByName
          .get(name)
          .toList
          .flatMap(_.toList)
          .filter(sym => ownerScope.contains(sym.owner))
          .map(canonicalOwner)
      }
    val fromNames =
      captureOwnerNames(tpe).flatMap { name =>
        ownerChain(owner).flatMap(_.paramSymss.flatten).filter {
          _.name.toString == name
        }
      }
    val fromSet =
      try {
        val direct =
          CapturingType
            .decomposeCapturingType(tpe)
            .map((_, cs) => symbolsFrom(cs))
            .getOrElse(Nil)
        if direct.nonEmpty then direct
        else symbolsFrom(CaptureSet.ofType(tpe, followResult = false))
      } catch {
        case _: Throwable => Nil
      }
    (fromSet ++ fromNames ++ localFromNames).map(canonicalOwner).distinct
  }

  private def expectedType(vd: ValDef)(using Context): Type =
    if typeMentionsRiftCapture(vd.tpt.tpe) then vd.tpt.tpe else vd.tpe

  private def markInferredAllocation(
      expected: Type,
      rhs: Tree,
      owner: Symbol,
      target: Symbol
  )(using Context): Unit =
    directReturnedNewApplies(rhs).foreach { app =>
      val owners =
        captureOwnerSymbols(expected, owner)
          .distinct
          .filter(isRiftInferredAllocationOwnerSymbol)
      owners match {
        case owner :: Nil =>
          markDirectConstructOwner(
            app,
            owner,
            "direct new or branch-returned new expected type is captured by a checked region"
          )
          markRegionOwner(
            target,
            owner,
            "direct new or branch-returned new expected type is captured by a checked region",
            app.srcPos
          )
          markNestedClosureOwnerConstrainedAllocations(
            owner,
            rhs,
            "nested direct closure value is constrained by a captured expected type",
            "nested local closure value is constrained by a captured expected type",
            "nested closure body return is constrained by a captured expected type",
            "conflicting inferred nested expected-type local closure owners"
          )
        case first :: second :: _ =>
          markRejected(
            target,
            s"ambiguous captured expected type owners: ${first.name} and ${second.name}",
            app.srcPos
          )
        case _ => ()
      }
    }

  private def markInferredClosureAllocation(
      expected: Type,
      rhs: Tree,
      owner: Symbol,
      target: Symbol
  )(using Context): Unit =
    directReturnedClosures(rhs).foreach { closure =>
      val owners =
        (captureOwnerSymbols(expected, owner) ++
          closure.env.flatMap(env => captureOwnerSymbols(env.tpe, owner)))
          .distinct
          .filter(isRiftInferredAllocationOwnerSymbol)
      owners match {
        case owner :: Nil =>
          closure.putAttachment(
            NirDefinitions.InferredRiftAllocationOwner,
            owner
          )
          markRegionOwner(
            target,
            owner,
            "closure expected type is captured by a checked region",
            closure.srcPos
          )
          markClosureBodyOwner(
            closure,
            owner,
            "closure body return is constrained by a captured checked region owner"
          )
          // Also mark the allocation effect for effect-polymorphic closures.
          // This enables the caller to inject the owner handle at the call site
          // even if the closure doesn't explicitly capture the owner.
          markClosureAllocationEffect(
            closure,
            owner,
            "closure expected type has an allocation effect in a checked region"
          )
        case first :: second :: _ =>
          markRejected(
            target,
            s"ambiguous captured closure owners: ${first.name} and ${second.name}",
            closure.srcPos
          )
        case Nil =>
          // No owner found from captured types, but check if the expected type
          // itself has a captured owner that could serve as an allocation effect.
          // This handles the case where the closure type is e.g. Function1[Int, T]^{r}
          // but the closure body doesn't explicitly capture r.
          val expectedOnlyOwners = captureOwnerSymbols(expected, owner)
            .distinct
            .filter(isRiftInferredAllocationOwnerSymbol)
          expectedOnlyOwners match {
            case effectOwner :: Nil =>
              markClosureAllocationEffect(
                closure,
                effectOwner,
                "closure expected type has an allocation effect in a checked region (expected-type only)"
              )
            case _ => ()
          }
      }
    }

  private def markInferredReturnAllocation(dd: DefDef)(using Context): Unit = {
    val expectedTypes =
      List(dd.tpt.tpe, dd.tpe, dd.symbol.info.finalResultType)
        .filter(typeMentionsRiftCapture)
    val methodRegionParams = dd.symbol.paramSymss.flatten.toSet
    val apps = directReturnedNewApplies(dd.rhs)
    if apps.nonEmpty then {
      val capturedOwners = expectedTypes
        .flatMap { expected =>
          captureOwnerSymbols(expected, dd.symbol) ++
            captureOwnerSymbols(expected, dd.symbol.owner) ++
            captureOwnerSymbols(expected, ctx.owner)
        }
        .distinct
        .filter(isRiftInferredAllocationOwnerSymbol)
      val owners = capturedOwners.filter(methodRegionParams.contains)
      val lexicalOwner =
        if owners.isEmpty then
          capturedOwners match {
            case owner :: Nil if treeMentionsRuntimeOwnerValue(dd.rhs, owner) =>
              Some(canonicalOwner(owner))
            case _ =>
              None
          }
        else None
      apps.foreach { app =>
        owners match {
          case owner :: Nil =>
            markDirectConstructOwner(
              app,
              owner,
              "direct new method result type is captured by a checked region"
            )
            markMethodReturnOwner(dd, owner)
            updateDecision(
              dd.symbol,
              RiftRegionInference.AllocationOwner.Region(owner),
              "direct new method result type is captured by a checked region",
              app.srcPos
            )
            markNestedClosureOwnerConstrainedAllocations(
              owner,
              dd.rhs,
              "nested direct closure method result is captured by a checked region",
              "nested local closure method result is captured by a checked region",
              "nested closure method result body return is constrained by a captured checked region owner",
              "conflicting inferred nested method-result local closure owners"
            )
          case first :: second :: _ =>
            updateDecision(
              dd.symbol,
              RiftRegionInference.AllocationOwner.Rejected,
              s"ambiguous captured method result owners: ${first.name} and ${second.name}",
              app.srcPos
            )
          case _ =>
            lexicalOwner.foreach { owner =>
              markDirectConstructOwner(
                app,
                owner,
                "direct method result type is constrained by a unique lexical checked owner type and runtime owner term"
              )
              markMethodReturnOwner(dd, owner)
              updateDecision(
                dd.symbol,
                RiftRegionInference.AllocationOwner.Region(owner),
                "method returns direct allocation constrained by a unique lexical checked owner type and runtime owner term",
                app.srcPos
              )
              markNestedClosureOwnerConstrainedAllocations(
                owner,
                dd.rhs,
                "nested direct closure method result is constrained by a unique lexical checked owner type",
                "nested local closure method result is constrained by a unique lexical checked owner type",
                "nested closure method result body return is constrained by a unique lexical checked owner type",
                "conflicting inferred nested lexical method-result local closure owners"
              )
            }
        }
      }
      val firstApp = apps.head
      if owners.isEmpty && lexicalOwner.isEmpty && capturedOwners.nonEmpty then
        updateDecision(
          dd.symbol,
          RiftRegionInference.AllocationOwner.Heap,
          "captured method result owner is not a method parameter with a runtime handle",
          firstApp.srcPos
        )
    }
  }

  private def capturedMethodResultOwners(
      dd: DefDef,
      expectedTypes: List[Type]
  )(using Context): (List[Symbol], List[Symbol]) = {
    val methodRegionParams = dd.symbol.paramSymss.flatten.toSet
    val capturedOwners =
      expectedTypes
        .flatMap { expected =>
          captureOwnerSymbols(expected, dd.symbol) ++
            captureOwnerSymbols(expected, dd.symbol.owner) ++
            captureOwnerSymbols(expected, ctx.owner)
        }
        .map(canonicalOwner)
        .distinct
        .filter(isRiftInferredAllocationOwnerSymbol)
    capturedOwners -> capturedOwners.filter(methodRegionParams.contains)
  }

  private def markInferredReturnClosureAllocation(
      dd: DefDef
  )(using Context): Unit = {
    val closures = directReturnedClosures(dd.rhs)
    if closures.nonEmpty then {
      val methodExpectedTypes =
        List(dd.tpt.tpe, dd.tpe, dd.symbol.info.finalResultType)
      closures.foreach { closure =>
        val expectedTypes =
          methodExpectedTypes :+ closure.tpe
        val (capturedOwners, owners) =
          capturedMethodResultOwners(dd, expectedTypes)
        owners match {
          case owner :: Nil =>
            closure.putAttachment(
              NirDefinitions.InferredRiftAllocationOwner,
              owner
            )
            markMethodReturnOwner(dd, owner)
            updateDecision(
              dd.symbol,
              RiftRegionInference.AllocationOwner.Region(owner),
              "closure method result type is captured by a checked region",
              closure.srcPos
            )
            markClosureBodyOwner(
              closure,
              owner,
              "method-returned closure body return is constrained by a captured checked region owner"
            )
          case first :: second :: _ =>
            updateDecision(
              dd.symbol,
              RiftRegionInference.AllocationOwner.Rejected,
              s"ambiguous captured closure method result owners: ${first.name} and ${second.name}",
              closure.srcPos
            )
          case _ =>
            if capturedOwners.nonEmpty then
              updateDecision(
                dd.symbol,
                RiftRegionInference.AllocationOwner.Heap,
                "captured closure method result owner is not a method parameter with a runtime handle",
                closure.srcPos
              )
        }
      }
    }
  }

  private def markMethodLocalReturnConstrainedAllocation(
      dd: DefDef
  )(using Context): Unit = {
    val expectedTypes =
      List(dd.tpt.tpe, dd.tpe, dd.symbol.info.finalResultType)
        .filter(typeMentionsRiftCapture)
    val methodRegionParams = dd.symbol.paramSymss.flatten.toSet
    returnedLocalIdents(dd.rhs).foreach { target =>
      val allocationTargets = localAllocationTargets(target).distinct
      val capturedOwners =
        expectedTypes
          .flatMap { expected =>
            captureOwnerSymbols(expected, dd.symbol) ++
              captureOwnerSymbols(expected, dd.symbol.owner) ++
              captureOwnerSymbols(expected, ctx.owner)
          }
          .distinct
          .filter(isRiftInferredAllocationOwnerSymbol)
      val owners = capturedOwners.filter(methodRegionParams.contains)
      val existingOwner =
        RiftRegionInference.inferredAllocationOwners
          .get(target)
          .map(canonicalOwner)
      val existingAllocationOwners =
        allocationTargets.flatMap { allocationTarget =>
          RiftRegionInference.inferredAllocationOwners
            .get(allocationTarget)
            .map(canonicalOwner)
        }.distinct
      if allocationTargets.nonEmpty || existingOwner.nonEmpty then
        owners match {
          case owner :: Nil =>
            val canonical = canonicalOwner(owner)
            val conflicting =
              (existingOwner.toList ++ existingAllocationOwners)
                .find(_ != canonical)
            conflicting match {
              case Some(previous) =>
                val rejectedTargets =
                  if allocationTargets.nonEmpty then target :: allocationTargets
                  else target :: Nil
                rejectedTargets.distinct.foreach { rejectedTarget =>
                  markRejected(
                    rejectedTarget,
                    s"conflicting inferred method-local owners: ${previous.name} and ${canonical.name}",
                    dd.rhs.srcPos
                  )
                }
              case None =>
                markSelectedAliasOwner(
                  target,
                  allocationTargets,
                  canonical,
                  "selected local allocation alias is returned from a method with a captured region result",
                  dd.rhs.srcPos
                )
                allocationTargets.foreach { allocationTarget =>
                  markLocalRegionConstructOwner(
                    allocationTarget,
                    canonical,
                    "local direct new or selected allocation is returned from a method with a captured region result",
                    dd.rhs.srcPos
                  )
                }
                markMethodReturnOwner(dd, canonical)
                updateDecision(
                  dd.symbol,
                  RiftRegionInference.AllocationOwner.Region(canonical),
                  "method returns local region allocation captured by a checked region",
                  dd.rhs.srcPos
                )
            }
          case first :: second :: _ =>
            val rejectedTargets =
              if allocationTargets.nonEmpty then target :: allocationTargets
              else target :: Nil
            rejectedTargets.distinct.foreach { rejectedTarget =>
              markRejected(
                rejectedTarget,
                s"ambiguous captured method result owners: ${first.name} and ${second.name}",
                dd.rhs.srcPos
              )
            }
          case _ =>
            capturedOwners match {
              case owner :: Nil
                  if treeMentionsRuntimeOwnerValue(dd.rhs, owner) =>
                val canonical = canonicalOwner(owner)
                val conflicting =
                  (existingOwner.toList ++ existingAllocationOwners)
                    .find(_ != canonical)
                conflicting match {
                  case Some(previous) =>
                    val rejectedTargets =
                      if allocationTargets.nonEmpty then target :: allocationTargets
                      else target :: Nil
                    rejectedTargets.distinct.foreach { rejectedTarget =>
                      markRejected(
                        rejectedTarget,
                        s"conflicting inferred lexical-owner method-local owners: ${previous.name} and ${canonical.name}",
                        dd.rhs.srcPos
                      )
                    }
                  case None =>
                    markSelectedAliasOwner(
                      target,
                      allocationTargets,
                      canonical,
                      "selected local allocation alias is returned from a method with a unique lexical checked owner type",
                      dd.rhs.srcPos
                    )
                    allocationTargets.foreach { allocationTarget =>
                      markLocalRegionConstructOwner(
                        allocationTarget,
                        canonical,
                        "local direct new or selected allocation is returned from a method with a unique lexical checked owner type and runtime owner term",
                        dd.rhs.srcPos
                      )
                    }
                    markMethodReturnOwner(dd, canonical)
                    updateDecision(
                      dd.symbol,
                      RiftRegionInference.AllocationOwner.Region(canonical),
                      "method returns local region allocation constrained by a unique lexical checked owner type and runtime owner term",
                      dd.rhs.srcPos
                    )
                }
              case _ =>
                if capturedOwners.nonEmpty then
                  updateDecision(
                    dd.symbol,
                    RiftRegionInference.AllocationOwner.Heap,
                    "captured method result owner is not a method parameter with a runtime handle",
                    dd.rhs.srcPos
                  )
            }
        }
    }
  }

  private def markMethodLocalReturnConstrainedClosure(
      dd: DefDef
  )(using Context): Unit = {
    val returnedClosures =
      returnedLocalIdents(dd.rhs).flatMap { target =>
        localClosureAllocationPairs(target)
      }
    if returnedClosures.nonEmpty then {
      val methodExpectedTypes =
        List(dd.tpt.tpe, dd.tpe, dd.symbol.info.finalResultType)
      returnedClosures.foreach { (target, closure) =>
        val expectedTypes = methodExpectedTypes :+ closure.tpe
        val (capturedOwners, owners) =
          capturedMethodResultOwners(dd, expectedTypes)
        owners match {
          case owner :: Nil =>
            val canonical = canonicalOwner(owner)
            val existingOwner =
              RiftRegionInference.inferredAllocationOwners
                .get(target)
                .map(canonicalOwner)
            existingOwner match {
              case Some(previous) if previous != canonical =>
                markRejected(
                  target,
                  s"conflicting inferred local closure owners: ${previous.name} and ${canonical.name}",
                  dd.rhs.srcPos
                )
              case _ =>
                closure.putAttachment(
                  NirDefinitions.InferredRiftAllocationOwner,
                  canonical
                )
                markRegionOwner(
                  target,
                  canonical,
                  "local closure is returned from a method with a captured region result",
                  dd.rhs.srcPos
                )
                markMethodReturnOwner(dd, canonical)
                updateDecision(
                  dd.symbol,
                  RiftRegionInference.AllocationOwner.Region(canonical),
                  "method returns local closure captured by a checked region",
                  dd.rhs.srcPos
                )
                markClosureBodyOwner(
                  closure,
                  canonical,
                  "method-returned local closure body return is constrained by a captured checked region owner"
                )
            }
          case first :: second :: _ =>
            markRejected(
              target,
              s"ambiguous captured local closure method result owners: ${first.name} and ${second.name}",
              dd.rhs.srcPos
            )
            updateDecision(
              dd.symbol,
              RiftRegionInference.AllocationOwner.Rejected,
              s"ambiguous captured local closure method result owners: ${first.name} and ${second.name}",
              dd.rhs.srcPos
            )
          case _ =>
            capturedOwners match {
              case owner :: Nil =>
                val canonical = canonicalOwner(owner)
                val existingOwner =
                  RiftRegionInference.inferredAllocationOwners
                    .get(target)
                    .map(canonicalOwner)
                existingOwner match {
                  case Some(previous) if previous != canonical =>
                    markRejected(
                      target,
                      s"conflicting inferred lexical-owner local closure owners: ${previous.name} and ${canonical.name}",
                      dd.rhs.srcPos
                    )
                  case _ =>
                    closure.putAttachment(
                      NirDefinitions.InferredRiftAllocationOwner,
                      canonical
                    )
                    markClosureSourceOwner(closure, canonical)
                    markRegionOwner(
                      target,
                      canonical,
                      "local closure is returned from a method with a unique lexical checked owner type",
                      dd.rhs.srcPos
                    )
                    markMethodReturnOwner(dd, canonical)
                    updateDecision(
                      dd.symbol,
                      RiftRegionInference.AllocationOwner.Region(canonical),
                      "method returns local closure constrained by a unique lexical checked owner type",
                      dd.rhs.srcPos
                    )
                    markClosureBodyOwner(
                      closure,
                      canonical,
                      "method-returned local closure body return is constrained by a unique lexical checked owner type"
                    )
                }
              case _ =>
                if capturedOwners.nonEmpty then {
                  updateDecision(
                    target,
                    RiftRegionInference.AllocationOwner.Heap,
                    "captured local closure method result owner is not a method parameter and no unique runtime owner value is captured",
                    dd.rhs.srcPos
                  )
                  updateDecision(
                    dd.symbol,
                    RiftRegionInference.AllocationOwner.Heap,
                    "captured local closure method result owner is not a method parameter and no unique runtime owner value is captured",
                    dd.rhs.srcPos
                  )
                }
            }
        }
      }
    }
  }

  private def markClosureBodyReturnConstrainedAllocation(
      dd: DefDef
  )(using Context): Unit =
    RiftRegionInference.inferredClosureBodyOwners
      .get(dd.symbol)
      .orElse(RiftRegionInference.inferredClosureValueOwners.get(dd.symbol))
      .foreach {
      owner =>
        val canonical = canonicalOwner(owner)
        directReturnedNewApplies(dd.rhs).foreach { app =>
          markDirectConstructOwner(
            app,
            canonical,
            "direct closure-body result is captured by a checked region owner"
          )
        }
        markNestedClosureOwnerConstrainedAllocations(
          canonical,
          dd.rhs,
          "nested direct closure-body closure result is captured by a checked region owner",
          "nested local closure-body closure result is captured by a checked region owner",
          "nested closure-body closure return is constrained by a checked region owner",
          "conflicting inferred nested closure-body local closure owners"
        )
        markNestedClosureAliasesInRegionConstruct(
          canonical,
          dd.rhs,
          "nested selected closure-body alias is constrained by a checked region owner",
          "nested selected local closure-body closure result is captured by a checked region owner",
          "nested selected closure-body closure return is constrained by a checked region owner",
          "conflicting inferred nested selected closure-body local closure owners"
        )
        returnedLocalIdents(dd.rhs).foreach { target =>
          val allocationTargets = localAllocationTargets(target).distinct
          val existingOwner =
            RiftRegionInference.inferredAllocationOwners
              .get(target)
              .map(canonicalOwner)
          val existingAllocationOwners =
            allocationTargets.flatMap { allocationTarget =>
              RiftRegionInference.inferredAllocationOwners
                .get(allocationTarget)
                .map(canonicalOwner)
            }.distinct
          if allocationTargets.nonEmpty || existingOwner.nonEmpty
          then
            val conflicting =
              (existingOwner.toList ++ existingAllocationOwners)
                .find(_ != canonical)
            conflicting match {
              case Some(previous) =>
                val rejectedTargets =
                  if allocationTargets.nonEmpty then target :: allocationTargets
                  else target :: Nil
                rejectedTargets.distinct.foreach { rejectedTarget =>
                  markRejected(
                    rejectedTarget,
                    s"conflicting inferred closure-body owners: ${previous.name} and ${canonical.name}",
                    dd.rhs.srcPos
                  )
                }
              case None =>
                markSelectedAliasOwner(
                  target,
                  allocationTargets,
                  canonical,
                  "selected local allocation alias is returned from a closure body captured by a checked region",
                  dd.rhs.srcPos
                )
                allocationTargets.foreach { allocationTarget =>
                  markLocalRegionConstructOwner(
                    allocationTarget,
                    canonical,
                    "local direct new or selected allocation is returned from a closure body captured by a checked region",
                    dd.rhs.srcPos
                  )
                }
                updateDecision(
                  dd.symbol,
                  RiftRegionInference.AllocationOwner.Region(canonical),
                  "closure body returns a local region allocation captured by a checked region",
                  dd.rhs.srcPos
                )
            }
        }
    }

  private def markClosureBodyReturnConstrainedClosure(
      dd: DefDef
  )(using Context): Unit =
    RiftRegionInference.inferredClosureBodyOwners
      .get(dd.symbol)
      .orElse(RiftRegionInference.inferredClosureValueOwners.get(dd.symbol))
      .foreach {
      owner =>
        val canonical = canonicalOwner(owner)
        directReturnedClosures(dd.rhs).foreach { closure =>
          markDirectClosureRegionOwner(
            closure,
            canonical,
            "direct closure-body closure result is captured by a checked region owner",
            "nested closure-body return is constrained by a checked region owner"
          )
          updateDecision(
            dd.symbol,
            RiftRegionInference.AllocationOwner.Region(canonical),
            "closure body returns a closure captured by a checked region",
            closure.srcPos
          )
        }
        returnedLocalIdents(dd.rhs)
          .flatMap(localClosureAllocationPairs)
          .distinct
          .foreach { (target, closure) =>
            markLocalClosureRegionOwner(
              target,
              closure,
              canonical,
              "local closure is returned from a closure body captured by a checked region",
              "nested local closure-body return is constrained by a checked region owner",
              "conflicting inferred closure-body local closure owners",
              dd.rhs.srcPos
            )
            updateDecision(
              dd.symbol,
              RiftRegionInference.AllocationOwner.Region(canonical),
              "closure body returns a local closure captured by a checked region",
              dd.rhs.srcPos
            )
          }
    }

  private def markEarlyClosureBodyReturnConstrainedClosure(
      dd: DefDef
  )(using Context): Unit =
    RiftRegionInference.inferredClosureBodyOwners
      .get(dd.symbol)
      .orElse(RiftRegionInference.inferredClosureValueOwners.get(dd.symbol))
      .foreach {
      owner =>
        val canonical = canonicalOwner(owner)
        directReturnedClosures(dd.rhs).foreach { closure =>
          markDirectClosureRegionOwner(
            closure,
            canonical,
            "direct closure-body closure result is captured by a checked region owner",
            "nested closure-body return is constrained by a checked region owner"
          )
          updateDecision(
            dd.symbol,
            RiftRegionInference.AllocationOwner.Region(canonical),
            "closure body returns a closure captured by a checked region",
            closure.srcPos
          )
        }
        val localClosures = directLocalClosureValDefs(dd.rhs)
        returnedLocalIdents(dd.rhs).distinct.foreach { target =>
          localClosures.get(target).foreach { closure =>
            markLocalClosureRegionOwner(
              target,
              closure,
              canonical,
              "local closure is returned from a closure body captured by a checked region",
              "nested local closure-body return is constrained by a checked region owner",
              "conflicting inferred closure-body local closure owners",
              dd.rhs.srcPos
            )
            updateDecision(
              dd.symbol,
              RiftRegionInference.AllocationOwner.Region(canonical),
              "closure body returns a local closure captured by a checked region",
              dd.rhs.srcPos
            )
          }
        }
    }

  private def markForwardedInferredMethodReturn(dd: DefDef)(using Context): Unit = {
    val calls = directReturnedInferredMethodCalls(dd.rhs)
    val localForwardOwners =
      returnedLocalIdents(dd.rhs)
        .flatMap(RiftRegionInference.inferredMethodReturnLocalOwners.get)
        .distinct
    if calls.nonEmpty || localForwardOwners.nonEmpty then {
      val expectedTypes =
        List(dd.tpt.tpe, dd.tpe, dd.symbol.info.finalResultType)
          .filter(typeMentionsRiftCapture)
      val methodRegionParams = dd.symbol.paramSymss.flatten.toSet
      val forwardedOwners =
        (
          calls.flatMap(app =>
            RiftRegionInference.inferredMethodReturnOwners.get(
              calledSymbol(app)
            )
          ) ::: localForwardOwners
        ).map(canonicalOwner).distinct
      val capturedOwners =
        expectedTypes
          .flatMap { expected =>
            captureOwnerSymbols(expected, dd.symbol) ++
              captureOwnerSymbols(expected, dd.symbol.owner) ++
              captureOwnerSymbols(expected, ctx.owner)
          }
          .distinct
          .filter(isRiftInferredAllocationOwnerSymbol)
      val owners = capturedOwners.filter(methodRegionParams.contains)
      owners match {
        case owner :: Nil =>
          markMethodReturnOwner(dd, owner)
          updateDecision(
            dd.symbol,
            RiftRegionInference.AllocationOwner.Region(owner),
            "method forwards an inferred region-returning method result",
            calls.headOption.map(_.srcPos).getOrElse(dd.rhs.srcPos)
          )
        case first :: second :: _ =>
          updateDecision(
            dd.symbol,
            RiftRegionInference.AllocationOwner.Rejected,
            s"ambiguous captured forwarded method result owners: ${first.name} and ${second.name}",
            calls.headOption.map(_.srcPos).getOrElse(dd.rhs.srcPos)
          )
        case _ =>
          capturedOwners match {
            case owner :: Nil
                if treeMentionsRuntimeOwnerValue(dd.rhs, owner) =>
              val canonical = canonicalOwner(owner)
              forwardedOwners match {
                case forwarded :: Nil if forwarded == canonical =>
                  markMethodReturnOwner(dd, canonical)
                  updateDecision(
                    dd.symbol,
                    RiftRegionInference.AllocationOwner.Region(canonical),
                    "method forwards an inferred region-returning method result constrained by a unique lexical checked owner type and runtime owner term",
                    calls.headOption.map(_.srcPos).getOrElse(dd.rhs.srcPos)
                  )
                case forwarded :: Nil =>
                  updateDecision(
                    dd.symbol,
                    RiftRegionInference.AllocationOwner.Rejected,
                    s"conflicting forwarded lexical method result owners: ${forwarded.name} and ${canonical.name}",
                    calls.headOption.map(_.srcPos).getOrElse(dd.rhs.srcPos)
                  )
                case first :: second :: _ =>
                  updateDecision(
                    dd.symbol,
                    RiftRegionInference.AllocationOwner.Rejected,
                    s"ambiguous forwarded lexical method result owners: ${first.name} and ${second.name}",
                    calls.headOption.map(_.srcPos).getOrElse(dd.rhs.srcPos)
                  )
                case Nil =>
                  updateDecision(
                    dd.symbol,
                    RiftRegionInference.AllocationOwner.Heap,
                    "forwarded method result has no concrete inferred owner to match the lexical checked owner",
                    calls.headOption.map(_.srcPos).getOrElse(dd.rhs.srcPos)
                  )
              }
            case _ =>
              if capturedOwners.nonEmpty then
                updateDecision(
                  dd.symbol,
                  RiftRegionInference.AllocationOwner.Heap,
                  "captured forwarded method result owner is not a method parameter with a runtime handle",
                  calls.headOption.map(_.srcPos).getOrElse(dd.rhs.srcPos)
                )
          }
      }
    }
  }

  private def markCapturedMethodParams(dd: DefDef)(using Context): Unit = {
    val methodRegionParams =
      dd.symbol.paramSymss.flatten.filterNot(_.isType).toSet
    dd.symbol.paramSymss.flatten
      .filterNot(_.isType)
      .filterNot(isRiftInferredAllocationOwnerSymbol)
      .foreach { param =>
        if typeMentionsRiftCapture(param.info) then {
          val owners =
            (captureOwnerSymbols(param.info, dd.symbol) ++
              captureOwnerSymbols(param.info, dd.symbol.owner) ++
              captureOwnerSymbols(param.info, ctx.owner))
              .map(canonicalOwner)
              .distinct
              .filter(isRiftInferredAllocationOwnerSymbol)
              .filter(methodRegionParams.contains)
          owners match {
            case owner :: Nil =>
              markRegionOwner(
                param,
                owner,
                "method parameter value is captured by a checked region",
                dd.srcPos
              )
            case first :: second :: _ =>
              updateDecision(
                param,
                RiftRegionInference.AllocationOwner.Rejected,
                s"ambiguous captured method parameter owners: ${first.name} and ${second.name}",
                dd.srcPos
              )
            case Nil => ()
          }
        }
      }
  }

  override def transformDefDef(dd: DefDef)(using Context): Tree = {
    dd.symbol.paramSymss.flatten.foreach { param =>
      if isRiftInferredAllocationOwnerSymbol(param) ||
        isRiftFrameworkOwnerTokenSymbol(param)
      then
        registerLocalCaptureOwner(param)
    }
    markCapturedMethodParams(dd)
    markInferredReturnAllocation(dd)
    markInferredReturnClosureAllocation(dd)
    markEarlyClosureBodyReturnConstrainedClosure(dd)
    val transformed = super.transformDefDef(dd)
    transformed match {
      case result: DefDef =>
        markClosureBodyReturnConstrainedAllocation(result)
        markClosureBodyReturnConstrainedClosure(result)
        markMethodLocalReturnConstrainedAllocation(result)
        markMethodLocalReturnConstrainedClosure(result)
        markForwardedInferredMethodReturn(result)
        // Perform escape analysis for automatic region scope inference
        analyzeEscapeBehavior(result)
        // TODO: Enable region scope insertion when the approach is refined.
        // The current issue is that marking allocations with synthetic
        // owners changes inference decisions but GenNIR can't create
        // actual regions for synthetic owners, causing test failures.
        //
        // Possible solutions:
        // 1. Don't mark allocations in inference phase - only track escape
        //    behavior. Let GenNIR handle the transformation.
        // 2. Use AST transformation to wrap method body in RiftRegion.scoped.
        // 3. Generate NIR code for region creation in GenNIR.
        // insertRegionScopes(result)
      case _ => ()
    }
    transformed
  }

  override def transformValDef(vd: ValDef)(using Context): Tree = {
    def rememberArrayElementOwner(owner: Symbol): Unit = {
      val canonical = canonicalOwner(owner)
      localArrayElementOwnerSyms.update(vd.symbol, canonical)
      RiftRegionInference.inferredArrayElementOwners.update(vd.symbol, canonical)
    }

    def forgetArrayElementOwner(): Unit = {
      localArrayElementOwnerSyms.remove(vd.symbol)
      RiftRegionInference.inferredArrayElementOwners.remove(vd.symbol)
    }

    def directApply(tree: Tree): Option[Apply] =
      tree match {
        case app: Apply                                    => Some(app)
        case Typed(expr, _)                                => directApply(expr)
        case Inlined(_, _, expr)                           => directApply(expr)
        case Block(_, expr)                                => directApply(expr)
        case TypeApply(Select(expr, nme.asInstanceOf_), _) => directApply(expr)
        case _                                             => None
      }

    if !vd.symbol.is(Mutable) then
      directLocalIdent(vd.rhs).foreach { target =>
        if isRiftInferredAllocationOwnerSymbol(target) then {
          childRegionOwnerSyms += vd.symbol
          localOwnerAliases.update(vd.symbol, canonicalOwner(target))
          registerLocalCaptureOwner(vd.symbol)
          updateDecision(
            vd.symbol,
            RiftRegionInference.AllocationOwner.Region(canonicalOwner(target)),
            "local checked region owner alias",
            vd.rhs.srcPos
          )
        } else if target.is(Mutable) &&
          (isRiftInferredAllocationOwnerType(target.info) ||
            isRiftStreamingRegionType(target.info))
        then
          markRejected(
            vd.symbol,
            "mutable checked region owner aliases are not inferred flow-sensitively yet",
            vd.rhs.srcPos
          )
      }
      directApply(vd.rhs).foreach { app =>
        checkedBufferGetOwnerAndValueType(app, calledSymbol(app)).foreach {
          (ownerSym, valueType) =>
            val owner = canonicalOwner(ownerSym)
            markRegionOwner(
              vd.symbol,
              owner,
              "local checked buffer get result is owned by checked buffer owner",
              vd.rhs.srcPos
            )
            capturedArrayElementOwnersFromTypes(
              valueType :: expectedType(vd) :: Nil
            ) match {
              case elementOwner :: Nil =>
                rememberArrayElementOwner(elementOwner)
              case _ =>
                forgetArrayElementOwner()
            }
        }
        checkedPriorityQueueResultOwnerAndValueType(
          app,
          calledSymbol(app)
        ).foreach { (ownerSym, valueType) =>
          val owner = canonicalOwner(ownerSym)
          markRegionOwner(
            vd.symbol,
            owner,
            "local checked priority queue result is owned by checked queue owner",
            vd.rhs.srcPos
          )
          capturedArrayElementOwnersFromTypes(
            valueType :: expectedType(vd) :: Nil
          ) match {
            case elementOwner :: Nil =>
              rememberArrayElementOwner(elementOwner)
            case _ =>
              forgetArrayElementOwner()
          }
        }
        checkedStreamRankResultOwnerAndValueType(
          app,
          calledSymbol(app)
        ).foreach { (ownerSym, valueType) =>
          val owner = canonicalOwner(ownerSym)
          markRegionOwner(
            vd.symbol,
            owner,
            "local checked stream rank result is owned by checked stream owner",
            vd.rhs.srcPos
          )
          val elementOwners =
            (
              capturedArrayElementOwnersFromTypes(
                valueType :: expectedType(vd) :: Nil,
                allowFrameworkOwnerToken = true
              ) ++ checkedStreamRankResultArrayElementOwners(app)
            ).map(canonicalOwner).distinct
          elementOwners match {
            case elementOwner :: Nil =>
              rememberArrayElementOwner(elementOwner)
            case _ =>
              forgetArrayElementOwner()
          }
        }
      }
      childRegionFactoryApply(vd.rhs).foreach { app =>
        childRegionOwnerSyms += vd.symbol
        registerLocalCaptureOwner(vd.symbol)
        updateDecision(
          vd.symbol,
          RiftRegionInference.AllocationOwner.Region(vd.symbol),
          "local child-region handle returned by a checked page/window/bucket owner",
          app.srcPos
        )
      }
    directRegionConstructApply(vd.rhs) match {
      case Some(app) if vd.symbol.is(Mutable) =>
        localRegionConstructAllocatedApps.remove(vd.symbol)
        markRejected(
          vd.symbol,
          "mutable local direct allocation is not inferred flow-sensitively yet",
          vd.rhs.srcPos
        )
      case Some(app) =>
        directlyNewAllocatedSyms += vd.symbol
        localRegionConstructAllocatedApps.update(vd.symbol, app :: Nil)
        updateDecision(
          vd.symbol,
          RiftRegionInference.AllocationOwner.Unknown,
          "direct new local has no checked region owner constraint yet",
          vd.rhs.srcPos
        )
      case None =>
        localRegionConstructAllocatedApps.remove(vd.symbol)
    }
    if vd.symbol.is(Mutable) then localNestedClosureAllocatedSyms.remove(vd.symbol)
    else {
      val nestedClosurePairs = nestedClosureAllocations(vd.rhs).distinct
      nestedClosurePairs match {
        case _ :: _ =>
          localNestedClosureAllocatedSyms.update(vd.symbol, nestedClosurePairs)
        case Nil =>
          localNestedClosureAllocatedSyms.remove(vd.symbol)
      }
    }
    if vd.symbol.is(Mutable) then localAllocatedSyms.remove(vd.symbol)
    else {
      val selectedLocalAllocations =
        returnedLocalIdents(vd.rhs).flatMap(localAllocationTargets).distinct
      selectedLocalAllocations match {
        case _ :: _ =>
          localAllocatedSyms.update(vd.symbol, selectedLocalAllocations)
          updateDecision(
            vd.symbol,
            RiftRegionInference.AllocationOwner.Unknown,
            "immutable local alias selects existing direct allocation locals",
            vd.rhs.srcPos
          )
        case Nil =>
          localAllocatedSyms.remove(vd.symbol)
      }
    }
    val forwardedOwners =
      if vd.symbol.is(Mutable) then Nil
      else
        directReturnedInferredMethodCalls(vd.rhs)
          .flatMap(app =>
            RiftRegionInference.inferredMethodReturnOwners.get(
              calledSymbol(app)
            )
          )
          .distinct
    forwardedOwners match {
      case owner :: Nil =>
        RiftRegionInference.inferredMethodReturnLocalOwners.update(
          vd.symbol,
          owner
        )
      case _ =>
        RiftRegionInference.inferredMethodReturnLocalOwners.remove(vd.symbol)
    }
    markInferredAllocation(expectedType(vd), vd.rhs, ctx.owner, vd.symbol)
    markLocalExpectedTypeConstrainedAllocation(
      expectedType(vd),
      vd.rhs,
      ctx.owner,
      "local direct new flows into a captured expected type"
    )
    if vd.symbol.is(Mutable) then
      directClosure(vd.rhs).foreach { _ =>
        directClosureAllocatedSyms.remove(vd.symbol)
        localClosureAllocatedSyms.remove(vd.symbol)
        markRejected(
          vd.symbol,
          "mutable local closure allocation is not inferred flow-sensitively yet",
          vd.rhs.srcPos
        )
      }
    else {
      val directClosurePairs =
        directReturnedClosures(vd.rhs).map(vd.symbol -> _)
      val selectedLocalClosures =
        returnedLocalIdents(vd.rhs).flatMap(localClosureAllocationPairs)
      val closurePairs =
        (directClosurePairs ++ selectedLocalClosures).distinct
      directClosure(vd.rhs) match {
        case Some(closure) =>
          directClosureAllocatedSyms.update(vd.symbol, closure)
        case None =>
          directClosureAllocatedSyms.remove(vd.symbol)
      }
      closurePairs match {
        case _ :: _ =>
          localClosureAllocatedSyms.update(vd.symbol, closurePairs)
        case Nil =>
          localClosureAllocatedSyms.remove(vd.symbol)
      }
      markInferredClosureAllocation(
        expectedType(vd),
        vd.rhs,
        ctx.owner,
        vd.symbol
      )
    }
    vd
  }

  override def transformAssign(assign: Assign)(using Context): Tree = {
    directlyNewAllocatedSyms -= assign.lhs.symbol
    localAllocatedSyms -= assign.lhs.symbol
    localNestedClosureAllocatedSyms -= assign.lhs.symbol
    directClosureAllocatedSyms -= assign.lhs.symbol
    localClosureAllocatedSyms -= assign.lhs.symbol
    markInferredAllocation(
      assign.lhs.tpe,
      assign.rhs,
      ctx.owner,
      assign.lhs.symbol
    )
    markLocalExpectedTypeConstrainedAllocation(
      assign.lhs.tpe,
      assign.rhs,
      ctx.owner,
      "local direct new is assigned into a captured location"
    )
    assign
  }

  override def transformApply(app: Apply)(using Context): Tree = {
    val sym = calledSymbol(app)
    if !sym.isClassConstructor then {
      val pairs = fullMethodArgumentPairs(app, sym)
      val ownerSubstitutions = methodOwnerSubstitutions(pairs)
      pairs.foreach { (param, value) =>
        markMethodArgumentExpectedTypeConstrainedAllocation(
          param.info,
          value,
          sym,
          ownerSubstitutions
        )
      }
    }
    app match {
      case Apply(Select(array, name), _ :: value :: Nil)
          if name.toString == "update" =>
        markArrayElementStoreConstrainedAllocation(array, value)
      case _ => ()
    }
    if isRiftRegionListPrepend(sym) && app.args.length >= 2 then
      markLocalOwnerConstrainedAllocation(
        app.args.head,
        app.args.last,
        "local direct new is constrained by checked RegionList prepend owner"
      )
    else
      checkedBufferAppendOwnerAndValue(app, sym).foreach { (owner, value) =>
        markLocalOwnerConstrainedAllocation(
          owner,
          value,
          "local direct new is constrained by checked buffer append owner"
        )
      }
    checkedPriorityQueueOwnerAndValues(app, sym).foreach { (owner, values) =>
      values.foreach { value =>
        markLocalOwnerConstrainedAllocation(
          owner,
          value,
          "local direct new is constrained by checked priority queue owner"
        )
      }
    }
    checkedStreamRankOwnerAndValues(app, sym).foreach { (owner, values) =>
      rememberCheckedStreamRankArrayElementOwner(app, owner, values)
      values.foreach { value =>
        markSelectedLocalOwnerConstrainedAllocation(
          owner,
          value,
          "selected local allocation alias is constrained by checked stream rank owner",
          allowFrameworkOwnerToken = true
        )
      }
    }
    app
  }

  // Escape analysis for automatic region scope inference.
  // This determines which allocation sites are local-escape (objects never
  // leave the current method) and can therefore be automatically placed
  // in a compiler-inserted region scope.
  private def analyzeEscapeBehavior(dd: DefDef)(using Context): Unit = {
    val methodSym = dd.symbol
    if methodSym == NoSymbol || methodSym.isConstructor then return

    // Collect all direct new allocations in the method body
    val allocations = collectDirectNewAllocations(dd.rhs)

    // For each allocation, determine its escape behavior
    allocations.foreach { app =>
      val sym = calledSymbol(app)
      if sym.isClassConstructor then {
        val allocatedSym = sym.owner
        if allocatedSym != NoSymbol then {
          // Check if the allocation is already region-placed
          val alreadyRegionPlaced =
            RiftRegionInference.inferredAllocationOwners
              .get(allocatedSym)
              .exists(isRiftInferredAllocationOwnerSymbol)

          if !alreadyRegionPlaced then {
            // Analyze where the allocated object flows
            val escapeBehavior = analyzeObjectEscape(app, dd.rhs)
            RiftRegionInference.allocationEscapeBehavior.update(
              allocatedSym,
              escapeBehavior
            )
          }
        }
      }
    }
  }

  // Collect all direct new allocations in a tree
  private def collectDirectNewAllocations(tree: Tree)(using Context): List[Apply] = {
    val builder = List.newBuilder[Apply]

    def traverse(current: Tree): Unit = current match {
      case app: Apply if directNewApply(app).isDefined =>
        builder += app
        // Don't traverse children - we found the allocation
      case app: Apply =>
        app.args.foreach(traverse)
        traverse(app.fun)
      case vd: ValDef =>
        traverse(vd.rhs)
      case dd: DefDef =>
        traverse(dd.rhs)
      case Block(stats, expr) =>
        stats.foreach(traverse)
        traverse(expr)
      case If(cond, thenp, elsep) =>
        traverse(cond)
        traverse(thenp)
        traverse(elsep)
      case Match(selector, cases) =>
        traverse(selector)
        cases.foreach { case CaseDef(_, guard, body) =>
          traverse(guard)
          traverse(body)
        }
      case Labeled(_, body) =>
        traverse(body)
      case Return(expr, _) =>
        traverse(expr)
      case Try(expr, cases, finalizer) =>
        traverse(expr)
        cases.foreach { case CaseDef(_, guard, body) =>
          traverse(guard)
          traverse(body)
        }
        traverse(finalizer)
      case WhileDo(cond, body) =>
        traverse(cond)
        traverse(body)
      case Typed(expr, _) =>
        traverse(expr)
      case Inlined(_, _, expr) =>
        traverse(expr)
      case Closure(env, fun, _) =>
        env.foreach(traverse)
        traverse(fun)
      case _ =>
        // Traverse children for other tree types
        current match {
          case tree: GenericApply =>
            traverse(tree.fun)
            tree.args.foreach(traverse)
          case tree: Apply =>
            traverse(tree.fun)
            tree.args.foreach(traverse)
          case _ => ()
        }
    }

    traverse(tree)
    builder.result()
  }

  // Analyze where an allocated object flows to determine its escape behavior
  private def analyzeObjectEscape(
      alloc: Apply,
      methodBody: Tree
  )(using Context): RiftRegionInference.EscapeBehavior = {
    // For now, use a conservative heuristic:
    // Only mark allocations as local-escape if they are stored in a
    // local val and all usages are simple reads (no field access,
    // no method calls, no closure capture).
    //
    // This is intentionally conservative to avoid breaking existing tests.
    // Future work can relax these constraints as the escape analysis
    // becomes more sophisticated.

    // Find the val that holds this allocation (if any)
    val parentVal = findParentValDef(alloc, methodBody)

    parentVal match {
      case Some(vd) if !vd.symbol.is(Mutable) =>
        // Check if the val is only used in simple ways
        val usages = findSymbolUsages(vd.symbol, methodBody)
        if usages.isEmpty then {
          // No usages - object is dead, treat as local-escape
          RiftRegionInference.EscapeBehavior.Local
        } else if usages.forall(isLocalUsage) then {
          // All usages are simple reads - object is local-escape
          RiftRegionInference.EscapeBehavior.Local
        } else {
          // Some usages are not simple reads - assume heap-escape
          RiftRegionInference.EscapeBehavior.Heap
        }
      case _ =>
        // No parent val or mutable val - assume heap-escape
        RiftRegionInference.EscapeBehavior.Heap
    }
  }

  // Find the ValDef that directly holds this allocation
  private def findParentValDef(
      alloc: Apply,
      methodBody: Tree
  )(using Context): Option[ValDef] = {
    var result: Option[ValDef] = None

    def traverse(current: Tree): Unit = current match {
      case vd: ValDef =>
        if isDirectNewApply(vd.rhs) && calledSymbol(vd.rhs) == calledSymbol(alloc) then
          result = Some(vd)
        else
          traverse(vd.rhs)
      case Block(stats, expr) =>
        stats.foreach(traverse)
        traverse(expr)
      case If(_, thenp, elsep) =>
        traverse(thenp)
        traverse(elsep)
      case Match(_, cases) =>
        cases.foreach { case CaseDef(_, _, body) => traverse(body) }
      case _ => ()
    }

    traverse(methodBody)
    result
  }

  // Find all usages of a symbol in a tree
  private def findSymbolUsages(
      sym: Symbol,
      tree: Tree
  )(using Context): List[Tree] = {
    val usages = List.newBuilder[Tree]

    def traverse(current: Tree): Unit = current match {
      case id: Ident if id.symbol == sym =>
        usages += id
      case Select(qualifier, _) if current.symbol == sym =>
        usages += current
        traverse(qualifier)
      case Apply(fun, args) =>
        traverse(fun)
        args.foreach(traverse)
      case TypeApply(fun, _) =>
        traverse(fun)
      case vd: ValDef =>
        traverse(vd.rhs)
      case dd: DefDef =>
        traverse(dd.rhs)
      case Block(stats, expr) =>
        stats.foreach(traverse)
        traverse(expr)
      case If(cond, thenp, elsep) =>
        traverse(cond)
        traverse(thenp)
        traverse(elsep)
      case Match(selector, cases) =>
        traverse(selector)
        cases.foreach { case CaseDef(_, guard, body) =>
          traverse(guard)
          traverse(body)
        }
      case Labeled(_, body) =>
        traverse(body)
      case Return(expr, _) =>
        traverse(expr)
      case Try(expr, cases, finalizer) =>
        traverse(expr)
        cases.foreach { case CaseDef(_, guard, body) =>
          traverse(guard)
          traverse(body)
        }
        traverse(finalizer)
      case WhileDo(cond, body) =>
        traverse(cond)
        traverse(body)
      case Typed(expr, _) =>
        traverse(expr)
      case Inlined(_, _, expr) =>
        traverse(expr)
      case Closure(env, fun, _) =>
        env.foreach(traverse)
        traverse(fun)
      case _ =>
        current match {
          case tree: GenericApply =>
            traverse(tree.fun)
            tree.args.foreach(traverse)
          case tree: Apply =>
            traverse(tree.fun)
            tree.args.foreach(traverse)
          case _ => ()
        }
    }

    traverse(tree)
    usages.result()
  }

  // Check if a usage is local (doesn't escape the method)
  private def isLocalUsage(tree: Tree)(using Context): Boolean = tree match {
    case id: Ident =>
      // Reading a local variable is local
      true
    case Select(qualifier, name) =>
      // Accessing a field of the object
      // This is a potential escape point - the object might be stored in a heap field
      // For safety, we conservatively treat field access as non-local
      false
    case Apply(fun, args) =>
      // Method call - the object might escape through the method
      // For safety, we conservatively treat method calls as non-local
      false
    case _ =>
      // Other usages - conservatively assume not local
      false
  }

  // Check if a tree is a direct new allocation
  private def isDirectNewApply(tree: Tree)(using Context): Boolean =
    directNewApply(tree).isDefined

  // Get the escape behavior for an allocation site
  private[nscplugin] def getEscapeBehavior(
      sym: Symbol
  ): Option[RiftRegionInference.EscapeBehavior] =
    RiftRegionInference.allocationEscapeBehavior.get(sym)

  // Check if an allocation site is local-escape
  private[nscplugin] def isLocalEscape(sym: Symbol): Boolean =
    getEscapeBehavior(sym).contains(RiftRegionInference.EscapeBehavior.Local)

  // Check if an allocation site is heap-escape
  private[nscplugin] def isHeapEscape(sym: Symbol): Boolean =
    getEscapeBehavior(sym).contains(RiftRegionInference.EscapeBehavior.Heap)

  // Region scope insertion for automatic region inference (Step 2.2).
  //
  // Current approach: Only track escape behavior without marking allocations.
  // GenNIR will use the escape information to decide whether to create regions.
  //
  // This avoids the issue of marking allocations with synthetic owners,
  // which changes inference decisions but GenNIR can't create actual regions
  // for synthetic owners.
  //
  // The escape analysis is performed in analyzeEscapeBehavior() which runs
  // before this function. The escape behavior is stored in
  // allocationEscapeBehavior and can be queried by GenNIR.
  //
  // Future work: GenNIR will use getEscapeBehavior() and isLocalEscape()
  // to decide whether to wrap allocations in RiftRegion.scoped.
  private def insertRegionScopes(dd: DefDef)(using Context): Unit = {
    val methodSym = dd.symbol
    if methodSym == NoSymbol || methodSym.isConstructor then return

    // Find all local-escape allocations in this method
    val localEscapeAllocations = collectDirectNewAllocations(dd.rhs)
      .filter { app =>
        val sym = calledSymbol(app)
        sym.isClassConstructor && {
          val allocatedSym = sym.owner
          allocatedSym != NoSymbol &&
            isLocalEscape(allocatedSym)
        }
      }

    if localEscapeAllocations.isEmpty then return

    // For now, we only track the escape behavior.
    // GenNIR will use this information to create regions.
    //
    // TODO: Implement actual region scope insertion when GenNIR can
    // create regions based on escape behavior.
  }

  // Create a synthetic region symbol for a method.
  // This symbol is used to mark allocations that should be automatically
  // placed in a compiler-inserted region scope.
  // GenNIR will use this to identify allocations and create actual regions.
  private def createSyntheticRegionSymbol(
      methodSym: Symbol
  )(using Context): Symbol = {
    // Create a unique name for the synthetic region
    val regionName =
      core.Names.termName(s"$$$${methodSym.name}$$autoRegion")

    // Create a synthetic val symbol for the region
    // This symbol will be used as the owner for allocations that should
    // be automatically placed in a region.
    val regionSym =
      core.Symbols.newSymbol(
        methodSym,
        regionName,
        core.Flags.Synthetic,
        defn.AnyType,
        coord = methodSym.coord
      )

    // Register the synthetic region as a valid region owner
    childRegionOwnerSyms += regionSym
    registerLocalCaptureOwner(regionSym)

    regionSym
  }
}
