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

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

import io.circe.{parser, Json}

/**
 * Minimal client for the Toxiproxy admin API, used to inject controllable network round-trip
 * time between the benchmark JVM and `benchmark-postgres` (see this repo's README topology
 * section).
 *
 * Deliberately does NOT create the proxy: `testdata/benchmark-pg/toxiproxy.json` is mounted
 * into the container and creates it at boot, so the proxy is already present for the many
 * consumers of port 5433 that never touch this API (the test suites, `AdventureWorksServer`,
 * the JMH classes in `benchmarksSql`). This object only adds and removes toxics.
 *
 * Every failure here throws. There is deliberately no fallback to an un-proxied or un-toxified
 * connection: silently benchmarking a connection that is not actually delayed would invalidate
 * every number in the phase with no visible symptom.
 */
object Toxiproxy {

  /**
   * Matches the `name` in `testdata/benchmark-pg/toxiproxy.json`.
   */
  val proxyName: String = "benchmark-postgres"

  private val upstreamToxicName = "latency_upstream"
  private val downstreamToxicName = "latency_downstream"

  /**
   * Overridable for the same reason `BenchmarkDb.jdbcUrl` is a constant: one place to change if
   * the topology ever moves off localhost.
   */
  def adminBaseUrl: String =
    sys.props.getOrElse("grackle.benchmarks.toxiproxyUrl", "http://localhost:8474")

  final case class Toxic(
      name: String,
      toxicType: String,
      stream: String,
      latencyMs: Int,
      jitterMs: Int
  )

  private val client: HttpClient = HttpClient.newHttpClient()

  private def send(request: HttpRequest, expected: Set[Int]): String = {
    val response =
      try client.send(request, HttpResponse.BodyHandlers.ofString())
      catch {
        case ex: Exception =>
          throw new IllegalStateException(
            s"Toxiproxy admin API at $adminBaseUrl is unreachable — is `sbt benchPgUp` running? " +
              s"(${request.method()} ${request.uri()})",
            ex)
      }
    if (!expected.contains(response.statusCode()))
      throw new IllegalStateException(
        s"Toxiproxy admin API returned ${response.statusCode()} for " +
          s"${request.method()} ${request.uri()}: ${response.body()}")
    response.body()
  }

  private def request(method: String, path: String, body: Option[Json]): HttpRequest = {
    val publisher =
      body.fold(HttpRequest.BodyPublishers.noBody())(json =>
        HttpRequest.BodyPublishers.ofString(json.noSpaces))
    HttpRequest
      .newBuilder(URI.create(s"$adminBaseUrl$path"))
      .header("Content-Type", "application/json")
      .method(method, publisher)
      .build()
  }

  private def toxicsPath: String = s"/proxies/$proxyName/toxics"

  /**
   * The toxics currently installed on the proxy, in the order the API reports them.
   */
  def listToxics(): List[Toxic] = {
    val body = send(request("GET", toxicsPath, None), Set(200))
    val json = parser
      .parse(body)
      .fold(
        failure =>
          throw new IllegalStateException(
            s"Toxiproxy returned a body that is not JSON: $body",
            failure),
        identity)
    json
      .asArray
      .getOrElse(
        throw new IllegalStateException(s"expected a JSON array of toxics, got: $body"))
      .toList
      .map { toxic =>
        def str(field: String): String =
          toxic
            .hcursor
            .get[String](field)
            .fold(
              err =>
                throw new IllegalStateException(
                  s"toxic is missing a String `$field`: $body",
                  err),
              identity)
        def attr(field: String): Int =
          toxic
            .hcursor
            .downField("attributes")
            .get[Int](field)
            .fold(
              err =>
                throw new IllegalStateException(
                  s"toxic is missing an Int `attributes.$field`: $body",
                  err),
              identity)
        Toxic(str("name"), str("type"), str("stream"), attr("latency"), attr("jitter"))
      }
  }

  /**
   * Removes every toxic on the proxy, restoring an un-delayed connection.
   */
  def clearToxics(): Unit =
    listToxics().foreach { toxic =>
      // 404 is tolerated: another caller may have removed it between the list and the delete,
      // and the desired end state — the toxic being gone — holds either way.
      // `val _ =` rather than a bare call: this build compiles with warnings as errors, and a
      // discarded non-Unit value trips `-Wvalue-discard`.
      val _ = send(request("DELETE", s"$toxicsPath/${toxic.name}", None), Set(204, 404))
    }

  /**
   * Installs a directional pair of `latency` toxics summing to `totalRttMs`, replacing any
   * toxics already present. `totalRttMs == 0` just clears.
   *
   * A pair rather than one toxic because real network latency accrues in both directions: a
   * single downstream toxic would give the same round-trip figure for a simple request/response
   * exchange but would misrepresent anything pipelined. Odd values are split unevenly (5ms
   * becomes 2 + 3) so the pair always sums to exactly the requested figure.
   *
   * Jitter is fixed at 0. Deterministic latency keeps run-to-run comparison clean, and this
   * suite has been bitten repeatedly by noise swamping real effects.
   */
  def setLatency(totalRttMs: Int): Unit = {
    require(totalRttMs >= 0, s"latency must not be negative, got $totalRttMs")
    clearToxics()
    if (totalRttMs > 0) {
      addLatencyToxic(upstreamToxicName, "upstream", totalRttMs / 2)
      addLatencyToxic(downstreamToxicName, "downstream", totalRttMs - totalRttMs / 2)
    }
  }

  private def addLatencyToxic(name: String, stream: String, latencyMs: Int): Unit = {
    val body = Json.obj(
      "name" -> Json.fromString(name),
      "type" -> Json.fromString("latency"),
      "stream" -> Json.fromString(stream),
      "toxicity" -> Json.fromDoubleOrNull(1.0),
      "attributes" -> Json.obj(
        "latency" -> Json.fromInt(latencyMs),
        "jitter" -> Json.fromInt(0)
      )
    )
    val _ = send(request("POST", toxicsPath, Some(body)), Set(200, 201))
  }
}
