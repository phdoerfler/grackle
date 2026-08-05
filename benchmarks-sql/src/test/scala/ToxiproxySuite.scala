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

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.typelevel.doobie.implicits._

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
    assertEquals(
      resweep.size,
      2,
      "a second setLatency must replace the first pair, not add to it")
    assertEquals(resweep.map(_.latencyMs).sum, 50)

    Toxiproxy.setLatency(0)
    assertEquals(Toxiproxy.listToxics(), Nil, "zero latency means no toxics at all")
  }

  test("an injected toxic actually delays real JDBC traffic through port 5433") {
    def timeSelect1(): Long = {
      val started = System.nanoTime()
      val one =
        sql"select 1".query[Int].unique.transact(BenchmarkDb.transactor[IO]).unsafeRunSync()
      assertEquals(one, 1, "sanity: the query itself must actually work")
      (System.nanoTime() - started) / 1000000L
    }

    // Pay the JVM's one-time JDBC/driver warm-up cost outside either measurement. In a cold
    // forked JVM the first `select 1` of the whole run takes seconds — SCRAM-SHA-256 auth
    // pulling from a cold SecureRandom — which would otherwise land entirely in `undelayed`
    // and swamp the toxic this test exists to detect, failing the test for a reason that has
    // nothing to do with the proxy.
    timeSelect1()

    Toxiproxy.clearToxics()
    val undelayed = timeSelect1()

    Toxiproxy.setLatency(50)
    val delayed = timeSelect1()

    // A generous floor, not an exact figure: the connection handshake alone crosses the proxy
    // several times, so the real delta is far larger than 50ms, but asserting a tight band would
    // make this test flaky on a loaded machine. What is being proven is binary — that the proxy
    // carries the traffic at all — so the assertion only needs to be well clear of noise.
    assert(
      delayed - undelayed > 40,
      s"a 50ms toxic changed `select 1` by only ${delayed - undelayed}ms " +
        s"($undelayed ms -> $delayed ms) — the proxy is almost certainly NOT in the connection " +
        "path, which would silently invalidate every measurement in this phase"
    )
  }
}
