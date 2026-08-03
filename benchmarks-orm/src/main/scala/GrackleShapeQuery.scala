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
 * that module untouched while still reusing its `hops`/`defaultRootCode` as the shared source
 * of truth for chain order and root selection.
 *
 * Field lists mirror the exact query that originally surfaced the BigDecimal.equals bug via
 * async-profiler (see `topic/sql-benchmarks` history): multiple leaf fields per hop, not just
 * one.
 */
object GrackleShapeQuery {
  private val wideLeafFields: Map[String, List[String]] =
    Map(
      "stateProvinces" -> List("name"),
      "addresses" -> List("city"),
      "businessEntityAddresses" -> List("addressTypeId"),
      "person" -> List("firstName", "lastName"),
      "customers" -> List("territoryId"),
      "salesOrders" -> List("totalDue"),
      "lineItems" -> List("orderQty", "unitPrice"),
      "product" -> List("name"),
      "subcategory" -> List("name"),
      "category" -> List("name")
    )

  private def nestWide(remaining: List[String]): String =
    remaining match {
      case field :: Nil => s"$field { ${wideLeafFields(field).mkString(" ")} }"
      case field :: rest => s"$field { ${wideLeafFields(field).mkString(" ")} ${nestWide(rest)} }"
      case Nil => throw new IllegalStateException("unreachable: depth bounds checked by Shape")
    }

  def queryFor(shape: Shape, rootCode: String = JoinChain.defaultRootCode): String =
    if (!shape.wideFields) JoinChain.queryForDepth(shape.depth, rootCode)
    else
      s"""query { countryRegions(code: "$rootCode") { countryRegionCode ${nestWide(
          JoinChain.hops.take(shape.depth))} } }"""
}
