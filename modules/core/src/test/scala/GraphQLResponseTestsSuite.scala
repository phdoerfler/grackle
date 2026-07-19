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

package grackle.test

import io.circe.literal._

// Direct unit tests for GraphQLResponseTests.weaklyEqual, which had none before this file - the
// strictPaths parameter's bug (never actually enforcing strict order for any array nested inside
// an object key, i.e. virtually every real response) went unnoticed for ~4 years because nothing
// exercised it directly; every prior caller only exercised it indirectly through a full mapping.
final class GraphQLResponseTestsSuite extends munit.CatsEffectSuite {

  private val ordered = json"""{"data":{"entities":[{"name":"Alpha"},{"name":"Bravo"}]}}"""
  private val reordered = json"""{"data":{"entities":[{"name":"Bravo"},{"name":"Alpha"}]}}"""

  test("default (no strictPaths) comparison is order-insensitive for a nested array") {
    assert(GraphQLResponseTests.weaklyEqual(ordered, reordered))
  }

  test("strictPaths targeting a nested array enforces order") {
    assert(
      !GraphQLResponseTests.weaklyEqual(
        ordered,
        reordered,
        List(List("data", "entities"))))
  }

  test("strictPaths targeting a nested array accepts matching order") {
    assert(
      GraphQLResponseTests.weaklyEqual(
        ordered,
        ordered,
        List(List("data", "entities"))))
  }

  test("strictPaths does not affect an array at an unrelated path") {
    val orderedTwoFields =
      json"""{"data":{"entities":[{"name":"Alpha"}],"other":[{"x":1},{"x":2}]}}"""
    val reorderedOther =
      json"""{"data":{"entities":[{"name":"Alpha"}],"other":[{"x":2},{"x":1}]}}"""
    assert(
      GraphQLResponseTests.weaklyEqual(
        orderedTwoFields,
        reorderedOther,
        List(List("data", "entities"))))
  }

  test("a root-level array (no object nesting) still enforces order with strictPaths = List(List())") {
    val a = json"""[{"name":"Alpha"},{"name":"Bravo"}]"""
    val b = json"""[{"name":"Bravo"},{"name":"Alpha"}]"""
    assert(!GraphQLResponseTests.weaklyEqual(a, b, List(List())))
  }
}
