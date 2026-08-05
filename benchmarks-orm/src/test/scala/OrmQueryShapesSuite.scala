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

import munit.FunSuite

class OrmQueryShapesSuite extends FunSuite {
  test("shapes span distinct depths, at least one shallow and one full-depth") {
    val depths = OrmQueryShapes.all.map(_.depth)
    assert(depths.exists(_ < grackle.benchmarks.sql.JoinChain.maxDepth))
    assert(depths.contains(grackle.benchmarks.sql.JoinChain.maxDepth))
  }

  test("exactly one shape is untuned") {
    assertEquals(OrmQueryShapes.all.count(!_.tuned), 1)
    assertEquals(OrmQueryShapes.untuned.tuned, false)
  }

  test("untuned shape's depth is distinct from shallow-narrow's and deep-*'s") {
    val untunedDepth = OrmQueryShapes.untuned.depth
    assert(untunedDepth != OrmQueryShapes.shallowNarrow.depth)
    assert(untunedDepth != OrmQueryShapes.deepNarrow.depth)
  }

  test("Selection.fieldsAt mirrors the GraphQL query's field selection") {
    // Narrow shapes select nothing at intermediate hops and one leaf field at the deepest hop
    // the shape reaches — exactly what JoinChain.nest emits.
    val narrow = OrmQueryShapes.deepNarrow
    assertEquals(Selection.fieldsAt("stateProvinces", narrow, isTerminal = false), Nil)
    assertEquals(Selection.fieldsAt("category", narrow, isTerminal = true), List("name"))

    // Wide shapes select their leaf fields at every hop, terminal or not.
    val wide = OrmQueryShapes.deepWide
    assertEquals(
      Selection.fieldsAt("person", wide, isTerminal = false),
      List("firstName", "lastName"))
    assertEquals(
      Selection.fieldsAt("lineItems", wide, isTerminal = false),
      List("orderQty", "unitPrice"))
    assertEquals(Selection.fieldsAt("category", wide, isTerminal = true), List("name"))
  }

  test("Selection.hopsFor stops at the shape's depth") {
    assertEquals(Selection.hopsFor(OrmQueryShapes.shallowNarrow).length, 3)
    assertEquals(Selection.hopsFor(OrmQueryShapes.deepNarrow).last, "category")
    assertEquals(Selection.hopsFor(OrmQueryShapes.untuned).length, 7)
  }

  test("Selection throws on an unknown hop rather than selecting nothing") {
    intercept[NoSuchElementException] {
      Selection.fieldsAt("notAHop", OrmQueryShapes.deepWide, isTerminal = false)
    }
  }
}
