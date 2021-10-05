/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v3_5.planner.logical

import org.neo4j.cypher.internal.v3_5.util.Rewritable._
import org.neo4j.cypher.internal.v3_5.util.{InputPosition, Rewriter, topDown}
import org.neo4j.cypher.internal.compiler.v3_5.phases.{PlannerContext, LogicalPlanState}
import org.neo4j.cypher.internal.v3_5.frontend.phases.{Condition, Phase}
import org.neo4j.cypher.internal.ir.v3_5._
import org.neo4j.cypher.internal.v3_5.expressions._
import org.neo4j.cypher.internal.v3_5.frontend.phases.CompilationPhaseTracer.CompilationPhase
import org.neo4j.cypher.internal.v3_5.frontend.phases.CompilationPhaseTracer.CompilationPhase.LOGICAL_PLANNING

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.collection.{TraversableOnce, mutable}

case object OptionalMatchRemover extends PlannerQueryRewriter {

  override def description: String = "remove optional match when possible"

  override def postConditions: Set[Condition] = Set.empty

  override def instance(ignored: PlannerContext): Rewriter = topDown(Rewriter.lift {
    case RegularPlannerQuery(graph, interestingOrder, proj@AggregatingQueryProjection(distinctExpressions, aggregations, _, _), tail)
      if validAggregations(aggregations) =>
      val projectionDeps: Iterable[LogicalVariable] = (distinctExpressions.values ++ aggregations.values).flatMap(_.dependencies)
      rewrite(projectionDeps, graph, interestingOrder, proj, tail)

    case RegularPlannerQuery(graph, interestingOrder, proj@DistinctQueryProjection(distinctExpressions, _, _), tail) =>
      val projectionDeps: Iterable[LogicalVariable] = distinctExpressions.values.flatMap(_.dependencies)
      rewrite(projectionDeps, graph, interestingOrder, proj, tail)
  })

  private def rewrite(projectionDeps: Iterable[LogicalVariable], graph: QueryGraph, interestingOrder: InterestingOrder, proj: QueryProjection, tail: Option[PlannerQuery]): RegularPlannerQuery = {
    val updateDeps = graph.mutatingPatterns.flatMap(_.dependencies)
    val dependencies: Set[String] = projectionDeps.map(_.name).toSet ++ updateDeps
    val gen = new PositionGenerator

    val optionalMatches = graph.optionalMatches.flatMapWithTail {
      (original: QueryGraph, tail: Seq[QueryGraph]) =>

        //The dependencies on an optional match are:
        val allDeps =
        // dependencies from optional matches listed later in the query
          tail.flatMap(g => g.argumentIds ++ g.selections.variableDependencies).toSet ++
            // any dependencies from the next horizon
            dependencies --
            // But we don't need to solve variables already present by the non-optional part of the QG
            graph.idsWithoutOptionalMatchesOrUpdates

        val mustInclude = allDeps -- original.argumentIds
        val mustKeep = smallestGraphIncluding(original, mustInclude)

        if (mustKeep.isEmpty)
        // We did not find anything in this OPTIONAL MATCH. Since there are no variable deps from this clause,
        // and it can't change cardinality, it's safe to ignore it
          None
        else {
          val ExtractionResult(predicatesForPatternExpression, predicatesToKeep, elementsToKeep) = {
            // We must keep all variables calculated in the previous step plus all arguments ids.
            val elementsToKeep0 = mustInclude ++ original.argumentIds

            // We must keep all variables connecting the so far elementsToKeep
            val elementsToKeep1 = smallestGraphIncluding(original, elementsToKeep0)

            // Now, if two relationships, that are currently not kept, overlap, unless the overlap is on an elementToKeep, we also need to keep the relationships.
            // Here, we keep the adjacent nodes, and in the next step we add the relationship itself
            val elementsToKeep2 = elementsToKeep1 ++ overlappingRels(original.patternRelationships, elementsToKeep1).flatMap(r => Seq(r.left, r.right))

            // We must (again) keep all variables connecting the so far elementsToKeep
            val elementsToKeep3 = smallestGraphIncluding(original, elementsToKeep2)

            extractElementsAndPatterns(original, elementsToKeep3)
          }

          val (patternsToKeep, patternsToFilter) = original.patternRelationships.partition(r => elementsToKeep(r.name))
          val patternNodes = original.patternNodes.filter(elementsToKeep.apply)

          val patternPredicates = patternsToFilter.map(toAst(elementsToKeep, predicatesForPatternExpression, gen, _))

          val newOptionalGraph = original.
            withPatternRelationships(patternsToKeep).
            withPatternNodes(patternNodes).
            withSelections(Selections.from(predicatesToKeep) ++ patternPredicates)

          Some(newOptionalGraph)
        }
    }

    val matches = graph.withOptionalMatches(optionalMatches)
    RegularPlannerQuery(matches, interestingOrder, horizon = proj, tail = tail)

  }

  /**
   * Given a set of relationships of the original query graph and a set of so-far elements to keep, return all relationships that must be kept.
   * These are all so-far kept relationships plus all other relationships that have an overlap, unless the overlap is on an elementToKeep.
   */
  private def overlappingRels(rels: Set[PatternRelationship], elementsToKeep: Set[String]): Set[PatternRelationship] = {
    val (keptRels, notYetKeptRels) = rels.partition(r => elementsToKeep(r.name))
    val alsoKeptRels = notYetKeptRels.filter { rel =>
      val relIds = rel.coveredIds -- elementsToKeep
      (notYetKeptRels - rel).exists { rel2 =>
        val rel2Ids = rel2.coveredIds -- elementsToKeep
        relIds.intersect(rel2Ids).nonEmpty
      }
    }
    keptRels ++ alsoKeptRels
  }

  /**
   * @param predicatesForPatternExpression predicates that can get moved into PatternExpressions.
   *                                       These are currently only `HasLabels`.
   *                                       This is a map from node variable name to the label names.
   * @param predicatesToKeep               predicate expressions that cannot be moved into patternExpressions
   * @param elementsToKeep                 node and relationship variables that cannot be moved into patternExpressions
   */
  case class ExtractionResult(predicatesForPatternExpression: Map[String, Seq[LabelName]], predicatesToKeep: Set[Expression], elementsToKeep: Set[String])

  @tailrec
  private def extractElementsAndPatterns(original: QueryGraph, elementsToKeepInitial: Set[String]): ExtractionResult = {
    val PartitionedPredicates(predicatesForPatterns, predicatesToKeep) = partitionPredicates(original.selections.predicates, elementsToKeepInitial)

    val variablesNeededForPredicates = predicatesToKeep.flatMap(expression => expression.dependencies.map(_.name))
    val elementsToKeep = smallestGraphIncluding(original, elementsToKeepInitial ++ variablesNeededForPredicates)

    if (elementsToKeep.equals(elementsToKeepInitial)) {
      ExtractionResult(predicatesForPatterns, predicatesToKeep, elementsToKeep)
    } else {
      extractElementsAndPatterns(original, elementsToKeep)
    }
  }

  /**
   * @param predicatesForPatternExpression predicates that can get moved into PatternExpressions.
   *                                       These are currently only `HasLabels`.
   *                                       This is a map from node variable name to the label names.
   * @param predicatesToKeep               predicate expressions that cannot be moved into patternExpressions
   */
  case class PartitionedPredicates(predicatesForPatternExpression: Map[String, Seq[LabelName]], predicatesToKeep: Set[Expression])

  /**
    * This method extracts predicates that need to be part of pattern expressions
    *
    * @param predicates All the original predicates of the QueryGraph
    * @param kept       Set of all variables that should not be moved to pattern expressions
    * @return Map of label predicates to move to pattern expressions,
    *         and the set of remaining predicates
    */
  private def partitionPredicates(predicates: Set[Predicate], kept: Set[String]): PartitionedPredicates = {

    val predicatesForPatternExpression = mutable.Map.empty[String, Seq[LabelName]]
    val predicatesToKeep = mutable.Set.empty[Expression]

    def addLabel(idName: String, labelName: LabelName) = {
      val current = predicatesForPatternExpression.getOrElse(idName, Seq.empty)
      predicatesForPatternExpression += idName -> (current :+ labelName)
    }

    predicates.foreach {
      case Predicate(deps, HasLabels(Variable(_), labels)) if deps.size == 1 && !kept(deps.head) =>
        assert(labels.size == 1) // We know there is only a single label here because AST rewriting
        addLabel(deps.head, labels.head)

      case Predicate(_, expr) =>
        predicatesToKeep += expr
    }

    PartitionedPredicates(predicatesForPatternExpression.toMap, predicatesToKeep.toSet)
  }

  private def validAggregations(aggregations: Map[String, Expression]) =
    aggregations.isEmpty ||
      aggregations.values.forall {
        case func: FunctionInvocation => func.distinct
        case _ => false
      }

  private class PositionGenerator {
    private var pos: InputPosition = InputPosition.NONE

    def nextPosition(): InputPosition = {
      val current = pos
      //this is not nice but we want to make sure don't collide with "real positions"
      pos = pos.copy(offset = current.offset - 1)
      current
    }
  }

  private def toAst(elementsToKeep: Set[String], predicates: Map[String, Seq[LabelName]], gen: PositionGenerator, pattern: PatternRelationship): PatternExpression = {
    def createVariable(name: String): Option[Variable] =
      if (!elementsToKeep(name))
        None
      else {
        Some(Variable(name)(gen.nextPosition()))
      }

    def createNode(name: String): NodePattern = {
      val labels = predicates.getOrElse(name, Seq.empty)
      NodePattern(createVariable(name), labels = labels, properties = None)(gen.nextPosition())
    }

    val relName = createVariable(pattern.name)
    val leftNode = createNode(pattern.nodes._1)
    val rightNode = createNode(pattern.nodes._2)
    val relPattern = RelationshipPattern(relName, pattern.types, length = None, properties = None, pattern.dir)(
      gen.nextPosition())
    val chain = RelationshipChain(leftNode, relPattern, rightNode)(gen.nextPosition())
    PatternExpression(RelationshipsPattern(chain)(gen.nextPosition()))
  }

  implicit class FlatMapWithTailable(in: IndexedSeq[QueryGraph]) {
    def flatMapWithTail(f: (QueryGraph, Seq[QueryGraph]) => TraversableOnce[QueryGraph]): IndexedSeq[QueryGraph] = {

      @tailrec
      def recurse(that: QueryGraph, rest: Seq[QueryGraph], builder: mutable.Builder[QueryGraph, ListBuffer[QueryGraph]]): Unit = {
        builder ++= f(that, rest)
        if (rest.nonEmpty)
          recurse(rest.head, rest.tail, builder)
      }
      if (in.isEmpty)
        IndexedSeq.empty
      else {
        val builder = ListBuffer.newBuilder[QueryGraph]
        recurse(in.head, in.tail, builder)
        builder.result().toIndexedSeq
      }
    }
  }

  /**
   * Return all variables in the smallest graph that includes all of `mustInclude`.
   */
  def smallestGraphIncluding(qg: QueryGraph, mustInclude: Set[String]): Set[String] = {
    if (mustInclude.size < 2)
      mustInclude intersect qg.allCoveredIds
    else {
      val mustIncludeRels = qg.patternRelationships.filter(r => mustInclude(r.name))
      val mustIncludeNodes = mustInclude.intersect(qg.patternNodes) ++ mustIncludeRels.flatMap(r => Seq(r.left, r.right))
      var accumulatedElements = mustIncludeNodes
      for {
        lhs <- mustIncludeNodes
        rhs <- mustIncludeNodes
        if lhs < rhs
      } {
        accumulatedElements ++= findPathBetween(qg, lhs, rhs)
      }
      accumulatedElements ++ mustInclude
    }
  }

  private case class PathSoFar(end: String, alreadyVisited: Set[PatternRelationship])

  private def hasExpandedInto(from: Seq[PathSoFar], into: Seq[PathSoFar]): Seq[Set[String]] =
    for {lhs <- from
         rhs <- into
         if rhs.alreadyVisited.exists(p => p.coveredIds.contains(lhs.end))}
      yield {
        (lhs.alreadyVisited ++ rhs.alreadyVisited).flatMap(_.coveredIds)
      }


  private def expand(queryGraph: QueryGraph, from: Seq[PathSoFar]): Seq[PathSoFar] = {
    from.flatMap {
      case PathSoFar(end, alreadyVisited) =>
        queryGraph.patternRelationships.collect {
          case pr if !alreadyVisited(pr) && pr.coveredIds(end) =>
            PathSoFar(pr.otherSide(end), alreadyVisited + pr)
        }
    }
  }

  private def findPathBetween(qg: QueryGraph, startFromL: String, startFromR: String): Set[String] = {
    var l = Seq(PathSoFar(startFromL, Set.empty))
    var r = Seq(PathSoFar(startFromR, Set.empty))
    (0 to qg.patternRelationships.size) foreach { i =>
      if (i % 2 == 0) {
        l = expand(qg, l)
        val matches = hasExpandedInto(l, r)
        if (matches.nonEmpty)
          return matches.minBy(_.size)
      }
      else {
        r = expand(qg, r)
        val matches = hasExpandedInto(r, l)
        if (matches.nonEmpty)
          return matches.minBy(_.size)
      }
    }

    // Did not find any path. Let's do the safe thing and return everything
    qg.patternRelationships.flatMap(_.coveredIds)
  }


}

trait PlannerQueryRewriter extends Phase[PlannerContext, LogicalPlanState, LogicalPlanState] {
  override def phase: CompilationPhase = LOGICAL_PLANNING

  def instance(context: PlannerContext): Rewriter

  override def process(from: LogicalPlanState, context: PlannerContext): LogicalPlanState = {
    val query: UnionQuery = from.unionQuery
    val rewritten = query.endoRewrite(instance(context))
    from.copy(maybeUnionQuery = Some(rewritten))
  }
}