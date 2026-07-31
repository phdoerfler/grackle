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

package grackle.benchmarks.sql

class JoinChainSuite extends munit.FunSuite {

  test("queryForDepth(1) nests exactly one hop") {
    val q = JoinChain.queryForDepth(1)
    assert(q.contains("stateProvinces { name }"))
    assert(!q.contains("addresses"))
  }

  test("queryForDepth(maxDepth) nests every hop in order and stays balanced") {
    val q = JoinChain.queryForDepth(JoinChain.maxDepth)
    val expectedOrder = "countryRegionCode" :: JoinChain.hops
    val positions = expectedOrder.map(token => q.indexOf(token))
    assert(positions.forall(_ >= 0), s"missing expected token in: $q")
    assert(positions == positions.sorted, s"tokens out of order in: $q")
    assertEquals(q.count(_ == '{'), q.count(_ == '}'))
    assert(q.trim.startsWith("query {"))
    assert(q.contains("category { name }"))
  }

  test("depth below 1 is rejected") {
    intercept[IllegalArgumentException](JoinChain.queryForDepth(0))
  }

  test("depth above maxDepth is rejected") {
    intercept[IllegalArgumentException](JoinChain.queryForDepth(JoinChain.maxDepth + 1))
  }

  test("queryForDepth filters the root by the default country code") {
    val q = JoinChain.queryForDepth(2)
    assert(q.contains("""countryRegions(code: "FR")"""), s"missing root filter in: $q")
  }

  test("queryForDepth accepts an explicit root code") {
    val q = JoinChain.queryForDepth(2, "US")
    assert(q.contains("""countryRegions(code: "US")"""), s"missing root filter in: $q")
    assertEquals(q.count(_ == '{'), q.count(_ == '}'))
  }

  test("the documented full-chain root codes are the vetted six") {
    assertEquals(JoinChain.fullChainRootCodes.toSet, Set("US", "AU", "CA", "GB", "DE", "FR"))
    assert(JoinChain.fullChainRootCodes.contains(JoinChain.defaultRootCode))
  }
}
