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

import io.circe.Json

/**
 * Reads the scalar fields `Selection` says a shape selects off already-loaded Hibernate
 * entities, as circe `Json`.
 *
 * This is where the ORM arms' fidelity to Grackle's response actually lives. An unmatched
 * `(hop, field)` pair throws rather than yielding nothing, matching every other string-keyed
 * lookup in this codebase — a silently missing field would make the ORM arm look cheaper than
 * it is and would only surface as a parity-test diff, if at all.
 */
object OrmJson {

  private def str(v: String): Json = if (v == null) Json.Null else Json.fromString(v)

  private def int(v: Integer): Json =
    if (v == null) Json.Null else Json.fromInt(v.intValue)

  private def dec(v: java.math.BigDecimal): Json =
    if (v == null) Json.Null else Json.fromBigDecimal(BigDecimal(v))

  private val accessors: Map[(String, String), AnyRef => Json] =
    Map(
      ("stateProvinces", "name") -> (e => str(e.asInstanceOf[StateProvinceEntity].name)),
      ("addresses", "city") -> (e => str(e.asInstanceOf[AddressEntity].city)),
      ("businessEntityAddresses", "addressTypeId") ->
        (e => int(e.asInstanceOf[BusinessEntityAddressEntity].addressTypeId)),
      ("person", "firstName") -> (e => str(e.asInstanceOf[PersonEntity].firstName)),
      ("person", "lastName") -> (e => str(e.asInstanceOf[PersonEntity].lastName)),
      ("customers", "territoryId") -> (e => int(e.asInstanceOf[CustomerEntity].territoryId)),
      ("salesOrders", "totalDue") -> (e =>
        dec(e.asInstanceOf[SalesOrderHeaderEntity].totalDue)),
      ("lineItems", "orderQty") -> (e => int(e.asInstanceOf[SalesOrderDetailEntity].orderQty)),
      ("lineItems", "unitPrice") -> (e =>
        dec(e.asInstanceOf[SalesOrderDetailEntity].unitPrice)),
      ("product", "name") -> (e => str(e.asInstanceOf[ProductEntity].name)),
      ("subcategory", "name") -> (e => str(e.asInstanceOf[ProductSubcategoryEntity].name)),
      ("category", "name") -> (e => str(e.asInstanceOf[ProductCategoryEntity].name))
    )

  /**
   * Visible for testing the unmatched-key behaviour directly.
   */
  def accessor(hop: String, field: String): AnyRef => Json =
    accessors.getOrElse(
      (hop, field),
      throw new NoSuchElementException(s"no accessor for field '$field' at hop '$hop'"))

  /**
   * The `(fieldName, value)` pairs this shape selects at `hop`, in the order it selects them.
   */
  def scalarsFor(
      hop: String,
      entity: AnyRef,
      shape: Shape,
      isTerminal: Boolean): List[(String, Json)] =
    Selection.fieldsAt(hop, shape, isTerminal).map(f => f -> accessor(hop, f)(entity))
}
