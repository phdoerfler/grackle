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

package demo.felines.transport

import cats.effect.IO
import cats.syntax.all._
import io.circe.{parser, Json, ParsingFailure}
import org.http4s.{ParseFailure, QueryParamDecoder, Request, Response, ServerSentEvent}
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl

import grackle.Mapping

// Implements "distinct connection mode" of the GraphQL-over-SSE protocol
// (https://github.com/enisdenjo/graphql-sse/blob/master/PROTOCOL.md): the request itself *is* the
// subscribe operation - no separate init handshake like the two WS protocols have, and
// cancellation is just the client closing the connection. The request side reuses the same
// GET-query-param / POST-JSON-body convention GraphQLService.mkRoutes already uses for plain
// HTTP queries; the only difference is the client sends Accept: text/event-stream instead of
// Accept: application/json, so the response streams one SSE frame per subscription event instead
// of a single JSON body.
object GraphQLSse {

  def handler(mapping: Mapping[IO], req: Request[IO]): IO[Response[IO]] = {
    val dsl = new Http4sDsl[IO] {}
    import dsl._

    implicit val jsonQPDecoder: QueryParamDecoder[Json] =
      QueryParamDecoder[String].emap { s =>
        parser.parse(s).leftMap {
          case ParsingFailure(msg, _) => ParseFailure("Invalid variables", msg)
        }
      }

    object QueryMatcher extends QueryParamDecoderMatcher[String]("query")
    object OperationNameMatcher
        extends OptionalQueryParamDecoderMatcher[String]("operationName")
    object VariablesMatcher
        extends OptionalValidatingQueryParamDecoderMatcher[Json]("variables")

    def stream(query: String, op: Option[String], vars: Option[Json]): IO[Response[IO]] = {
      val events =
        mapping
          .compileAndRunSubscription(query, op, vars)
          .map(json => ServerSentEvent(data = Some(json.noSpaces), eventType = Some("next"))) ++
          fs2.Stream(ServerSentEvent(data = Some(""), eventType = Some("complete")))
      Ok(events)
    }

    req match {
      case GET -> Root / "cats" :?
          QueryMatcher(query) +& OperationNameMatcher(op) +& VariablesMatcher(vars0) =>
        vars0
          .sequence
          .fold(
            errors => BadRequest(errors.map(_.sanitized).mkString_("", ",", "")),
            vars => stream(query, op, vars)
          )
      case req @ POST -> Root / "cats" =>
        for {
          body <- req.as[Json]
          obj <- body.asObject.liftTo[IO](InvalidJsonBody)
          query <- obj("query").flatMap(_.asString).liftTo[IO](MissingQueryField)
          op = obj("operationName").flatMap(_.asString)
          vars = obj("variables")
          resp <- stream(query, op, vars)
        } yield resp
      case _ => NotFound()
    }
  }

  private case object InvalidJsonBody extends Throwable("Invalid GraphQL query")
  private case object MissingQueryField extends Throwable("Missing query field")
}
