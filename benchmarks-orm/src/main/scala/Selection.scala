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
 * Single source of truth for which scalar fields a `Shape` selects at each hop.
 *
 * Two consumers read from it and neither owns the lists: `GrackleShapeQuery` renders them into
 * GraphQL query text, and `OrmJson` renders them into a circe document from already-loaded
 * entities. If either hard-coded its own copy they could drift, and the benchmark would compare
 * different work with no visible symptom — which is why every string-keyed lookup here throws
 * on an unmatched key rather than returning an empty result that would read as "this arm did
 * less".
 */
object Selection {

  /**
   * Field lists for `wideFields = true` shapes. Mirrors the exact query that originally
   * surfaced the BigDecimal.equals bug via async-profiler: multiple leaf fields per hop, not
   * just one.
   */
  val wideLeafFields: Map[String, List[String]] =
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

  /**
   * The hops this shape traverses, in order.
   */
  def hopsFor(shape: Shape): List[String] = JoinChain.hops.take(shape.depth)

  /**
   * The scalar fields selected at `hop`. `isTerminal` means `hop` is the last element of
   * `hopsFor(shape)` — the deepest hop this shape reaches, not the deepest that exists.
   *
   * Narrow shapes carry no scalars at intermediate hops and exactly one at the terminal hop,
   * matching `JoinChain.nest`'s `field { nest(rest) }` / `field { leafField(field) }`. Wide
   * shapes carry their leaf fields at every hop, matching `GrackleShapeQuery.nestWide`.
   */
  def fieldsAt(hop: String, shape: Shape, isTerminal: Boolean): List[String] =
    if (shape.wideFields) wideLeafFields(hop)
    else if (isTerminal) List(JoinChain.leafField(hop))
    else if (JoinChain.leafField.contains(hop)) Nil
    else throw new NoSuchElementException(s"key not found: $hop")
}
