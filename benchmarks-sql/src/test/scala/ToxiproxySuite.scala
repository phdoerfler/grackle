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

import munit.FunSuite

class ToxiproxySuite extends FunSuite {

  // Leaving a toxic installed would silently slow down every subsequent suite in this module
  // (Test / parallelExecution is false, so they run after this one in the same JVM).
  override def afterEach(context: AfterEach): Unit = Toxiproxy.clearToxics()

  test("setLatency splits the target RTT across an upstream/downstream pair") {
    Toxiproxy.setLatency(20)

    val toxics = Toxiproxy.listToxics().sortBy(_.stream)
    assertEquals(toxics.map(_.stream), List("downstream", "upstream"))
    assertEquals(toxics.map(_.toxicType).distinct, List("latency"))
    assertEquals(
      toxics.map(_.latencyMs).sum,
      20,
      "the two directional toxics must sum to exactly the requested round-trip time")
    assertEquals(toxics.map(_.jitterMs).distinct, List(0), "jitter must stay deterministic")
  }

  test("an odd RTT still sums exactly, and setLatency neither accumulates nor leaks") {
    Toxiproxy.setLatency(5)
    assertEquals(
      Toxiproxy.listToxics().map(_.latencyMs).sum,
      5,
      "integer halving must not lose a millisecond")

    // Re-applying must replace, not add to, the previous level — otherwise a JMH sweep would
    // measure the sum of every level it had already visited.
    Toxiproxy.setLatency(50)
    val resweep = Toxiproxy.listToxics()
    assertEquals(resweep.size, 2, "a second setLatency must replace the first pair, not add to it")
    assertEquals(resweep.map(_.latencyMs).sum, 50)

    Toxiproxy.setLatency(0)
    assertEquals(Toxiproxy.listToxics(), Nil, "zero latency means no toxics at all")
  }
}
