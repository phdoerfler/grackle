// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// Copyright (c) 2016-2025 Grackle Contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package grackle.benchmarks.orm

import grackle.benchmarks.sql.JoinChain

/**
 * One shape definition drives all three benchmark arms: `depth` and `wideFields` are consumed
 * to build the GraphQL query text (Grackle arm, `GrackleShapeQuery`), the JPA entity traversal
 * (naive/eager arms), and the field-count read per hop (parity checks). `tuned` marks whether
 * the eager arm attaches a per-shape `EntityGraph` (Task 7) — false for `untuned`, which must
 * fall back to the blanket `@BatchSize` default only.
 */
final case class Shape(name: String, depth: Int, wideFields: Boolean, tuned: Boolean) {
  require(
    depth >= 1 && depth <= JoinChain.maxDepth,
    s"shape $name: depth must be between 1 and ${JoinChain.maxDepth}, got $depth")
}

object OrmQueryShapes {
  // A few hops, one field per hop.
  val shallowNarrow: Shape =
    Shape("shallow-narrow", depth = 3, wideFields = false, tuned = true)

  // Full depth, one field per hop — directly comparable to benchmarksSql's existing depth-10
  // numbers.
  val deepNarrow: Shape =
    Shape("deep-narrow", depth = JoinChain.maxDepth, wideFields = false, tuned = true)

  // Full depth, richer field selection per hop — mirrors the query shape that originally
  // surfaced the BigDecimal.equals bug via profiling.
  val deepWide: Shape =
    Shape("deep-wide", depth = JoinChain.maxDepth, wideFields = true, tuned = true)

  // Moderate depth (distinct from both 3 and 10), deliberately NOT tuned: the eager arm never
  // builds an entity graph for this shape, so it falls through to the blanket @BatchSize
  // default only — reproducing "new query shape nobody has optimized yet."
  val untuned: Shape = Shape("untuned", depth = 7, wideFields = true, tuned = false)

  val all: List[Shape] = List(shallowNarrow, deepNarrow, deepWide, untuned)

  require(all.map(_.name).distinct.size == all.size, "shape names must be unique")
}
