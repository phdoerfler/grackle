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
 * Builds the GraphQL query text for a `Shape`'s `wideFields = true` case. Deliberately NOT
 * added to `JoinChain` itself (in `benchmarksSql`, already complete and reviewed) — this keeps
 * the wide-shape query-building logic out of that module, reusing only its
 * `hops`/`defaultRootCode`/`leafField` as the shared source of truth for chain order, root
 * selection, and leaf-field names.
 *
 * Field lists mirror the exact query that originally surfaced the BigDecimal.equals bug via
 * async-profiler (see `topic/sql-benchmarks` history): multiple leaf fields per hop, not just
 * one.
 *
 * (Phase 4 note: `JoinChain.leafField` did have to become non-private so `Selection` can read
 * it — a deliberate, minimal exception to that rule, taken because the alternative was
 * duplicating the narrow leaf-field map and letting the two arms drift.)
 */
object GrackleShapeQuery {
  // `isTerminal` below is inert: `nestWide` is reachable only for `shape.wideFields = true`
  // shapes, and `Selection.fieldsAt`'s wide branch returns `wideLeafFields(hop)` regardless of
  // `isTerminal`. It's still threaded and computed per branch for uniformity with
  // `Selection.fieldsAt`'s narrow-shape signature, where the flag does matter.
  private def nestWide(remaining: List[String], shape: Shape): String =
    remaining match {
      case field :: Nil =>
        s"$field { ${Selection.fieldsAt(field, shape, isTerminal = true).mkString(" ")} }"
      case field :: rest =>
        s"$field { ${Selection.fieldsAt(field, shape, isTerminal = false).mkString(" ")} ${nestWide(rest, shape)} }"
      case Nil => throw new IllegalStateException("unreachable: depth bounds checked by Shape")
    }

  def queryFor(shape: Shape, rootCode: String = JoinChain.defaultRootCode): String =
    if (!shape.wideFields) JoinChain.queryForDepth(shape.depth, rootCode)
    else
      s"""query { countryRegions(code: "$rootCode") { countryRegionCode ${nestWide(
          Selection.hopsFor(shape),
          shape)} } }"""
}
