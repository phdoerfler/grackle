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
import munit.FunSuite

class OrmJsonSuite extends FunSuite {

  test("reads the wide fields of a person") {
    val p = new PersonEntity
    p.firstName = "Ada"
    p.lastName = "Lovelace"
    assertEquals(
      OrmJson.scalarsFor("person", p, OrmQueryShapes.deepWide, isTerminal = false),
      List("firstName" -> Json.fromString("Ada"), "lastName" -> Json.fromString("Lovelace"))
    )
  }

  test("reads a narrow shape's terminal leaf field, and nothing at intermediate hops") {
    val c = new ProductCategoryEntity
    c.name = "Bikes"
    assertEquals(
      OrmJson.scalarsFor("category", c, OrmQueryShapes.deepNarrow, isTerminal = true),
      List("name" -> Json.fromString("Bikes")))

    val sp = new StateProvinceEntity
    sp.name = "Charente-Maritime"
    assertEquals(
      OrmJson.scalarsFor("stateProvinces", sp, OrmQueryShapes.deepNarrow, isTerminal = false),
      Nil)
  }

  test("encodes decimals and boxed integers, and nulls as JSON null") {
    val d = new SalesOrderDetailEntity
    d.orderQty = Integer.valueOf(3)
    d.unitPrice = new java.math.BigDecimal("2024.9940")
    assertEquals(
      OrmJson.scalarsFor("lineItems", d, OrmQueryShapes.deepWide, isTerminal = false),
      List(
        "orderQty" -> Json.fromInt(3),
        "unitPrice" -> Json.fromBigDecimal(BigDecimal("2024.9940")))
    )

    // A null column must become JSON null, never a crash and never an omitted key: Grackle emits
    // the key with a null value for a nullable column, and the documents have to match.
    val empty = new SalesOrderDetailEntity
    assertEquals(
      OrmJson.scalarsFor("lineItems", empty, OrmQueryShapes.deepWide, isTerminal = false),
      List("orderQty" -> Json.Null, "unitPrice" -> Json.Null))
  }

  test("throws on a field with no accessor rather than silently omitting it") {
    intercept[NoSuchElementException] {
      OrmJson.accessor("person", "middleName")
    }
  }

  test("canonicalize sorts arrays and object keys, and normalizes decimal scale") {
    val a = Json.obj(
      "b" -> Json.arr(Json.fromString("y"), Json.fromString("x")),
      "a" -> Json.fromBigDecimal(BigDecimal("1.500")))
    val b = Json.obj(
      "a" -> Json.fromBigDecimal(BigDecimal("1.5")),
      "b" -> Json.arr(Json.fromString("x"), Json.fromString("y")))
    assertEquals(JsonCanonical.canonicalize(a), JsonCanonical.canonicalize(b))
  }

  test("canonicalize does not conflate genuinely different documents") {
    val a = Json.obj("xs" -> Json.arr(Json.fromInt(1), Json.fromInt(2)))
    val b = Json.obj("xs" -> Json.arr(Json.fromInt(1), Json.fromInt(3)))
    assertNotEquals(JsonCanonical.canonicalize(a), JsonCanonical.canonicalize(b))
  }
}
