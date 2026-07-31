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

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all._
import com.comcast.ip4s._
import io.circe.{parser, Json, ParsingFailure}
import org.http4s.{HttpRoutes, InvalidMessageBodyFailure, ParseFailure, QueryParamDecoder}
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._

import grackle.Mapping
import grackle.doobie.DoobieMonitor

/**
 * Ad-hoc HTTP server exposing `AdventureWorksMapping` so a human can poke at it with Postman (or
 * curl) instead of only reaching it via benchmarks and tests.
 *
 * Not part of the benchmark or test suites, and not wired into CI beyond format/header checks —
 * `benchmarksSql` is deliberately outside the `modules` aggregate (see the comment on
 * `lazy val benchmarksSql` in build.sbt), and this server doesn't change that.
 *
 * sbt "benchmarksSql/runMain grackle.benchmarks.sql.AdventureWorksServer"
 */
object AdventureWorksServer extends IOApp {

  val prefix: String = "adventureworks"
  val port: Port = port"8081"
  val host: Ipv4Address = ipv4"0.0.0.0"

  /**
   * Same request/response shape as `demo.GraphQLService.mkRoutes` (GET with a `query` string
   * param, POST with a `{query, operationName, variables}` JSON body) so the server behaves the
   * way a Grackle user already expects. Kept local rather than depending on the `demo` module,
   * which would be an odd coupling for a benchmarks module.
   */
  def mkRoutes(mapping: Mapping[IO]): HttpRoutes[IO] = {
    val dsl = new Http4sDsl[IO] {}
    import dsl._

    implicit val jsonQPDecoder: QueryParamDecoder[Json] =
      QueryParamDecoder[String].emap { s =>
        parser.parse(s).leftMap {
          case ParsingFailure(msg, _) => ParseFailure("Invalid variables", msg)
        }
      }

    object QueryMatcher extends QueryParamDecoderMatcher[String]("query")
    object OperationNameMatcher extends OptionalQueryParamDecoderMatcher[String]("operationName")
    object VariablesMatcher extends OptionalValidatingQueryParamDecoderMatcher[Json]("variables")

    HttpRoutes.of[IO] {
      // GraphQL query is embedded in the URI query string when queried via GET
      case GET -> Root / `prefix` :?
          QueryMatcher(query) +& OperationNameMatcher(op) +& VariablesMatcher(vars0) =>
        vars0
          .sequence
          .fold(
            errors => BadRequest(errors.map(_.sanitized).mkString_("", ",", "")),
            vars =>
              for {
                result <- mapping.compileAndRun(query, op, vars)
                resp <- Ok(result)
              } yield resp
          )

      // GraphQL query is embedded in a Json request body when queried via POST
      case req @ POST -> Root / `prefix` =>
        for {
          body <- req.as[Json]
          obj <- body.asObject.liftTo[IO](InvalidMessageBodyFailure("Invalid GraphQL query"))
          query <- obj("query")
            .flatMap(_.asString)
            .liftTo[IO](InvalidMessageBodyFailure("Missing query field"))
          op = obj("operationName").flatMap(_.asString)
          vars = obj("variables")
          result <- mapping.compileAndRun(query, op, vars)
          resp <- Ok(result)
        } yield resp
    }
  }

  /**
   * Pooled transactor: this server answers concurrent interactive requests, unlike
   * `SqlQueryCounts`'s one-shot harness, so the per-transaction connection setup that
   * `BenchmarkDb.transactor` accepts there would be the wrong trade-off here.
   */
  def mkServer: Resource[IO, Unit] =
    for {
      xa <- BenchmarkDb.transactorResource[IO]
      mapping = AdventureWorksMapping.mkMapping[IO](xa, DoobieMonitor.noopMonitor[IO])
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(mkRoutes(mapping).orNotFound)
        .build
    } yield ()

  val exampleQuery: String =
    """{"query":"query { countryRegions(code: \"FR\") """ +
      """{ countryRegionCode name stateProvinces { name addresses { city } } } }"}"""

  def printBanner: IO[Unit] =
    IO.println(s"AdventureWorks GraphQL server listening on http://$host:$port/$prefix") *>
      IO.println("Paste into Postman:") *>
      IO.println(s"  POST http://localhost:$port/$prefix") *>
      IO.println(s"  Body (raw JSON): $exampleQuery") *>
      IO.println("Or via GET:") *>
      IO.println(
        s"""  GET http://localhost:$port/$prefix?query=query%20%7B%20countryRegions(code%3A%20%22FR%22)%20%7B%20countryRegionCode%20name%20%7D%20%7D""") *>
      IO.println("Ctrl-C to stop.")

  def run(args: List[String]): IO[ExitCode] =
    mkServer.evalMap(_ => printBanner).useForever.as(ExitCode.Success)
}
