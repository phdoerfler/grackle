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

import scala.concurrent.duration._

import cats.effect.{Fiber, IO, Ref}
import cats.effect.std.Queue
import cats.syntax.all._
import fs2.Stream
import io.circe.{parser, Json, JsonObject}
import io.circe.syntax._
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.typelevel.log4cats.Logger

import grackle.Mapping

// Implements the graphql-transport-ws protocol (the "graphql-ws" library's modern subprotocol)
// over a WebSocket. This is what Postman and current Apollo Client use for GraphQL subscriptions.
//
// Flow: client connects with subprotocol graphql-transport-ws and sends connection_init; the
// server replies connection_ack. The client then sends `subscribe` messages (each with an id);
// the server streams `next` frames and a final `complete`. A client `complete` cancels that
// subscription.
//
// The caller (CatsRoutes) is responsible for the wsb it hands in already carrying the correct
// Sec-WebSocket-Protocol response header, or none at all - RFC 6455 forbids a server confirming a
// subprotocol the client never offered, so this module never sets that header itself.
object ModernGraphQLWs {

  private type Subs = Ref[IO, Map[String, Fiber[IO, Throwable, Unit]]]

  private def text(j: Json): WebSocketFrame = WebSocketFrame.Text(j.noSpaces)
  private val ackMsg = Json.obj("type" -> "connection_ack".asJson)
  private val pongMsg = Json.obj("type" -> "pong".asJson)
  private def nextMsg(id: String, payload: Json) =
    Json.obj("id" -> id.asJson, "type" -> "next".asJson, "payload" -> payload)
  private def completeMsg(id: String) =
    Json.obj("id" -> id.asJson, "type" -> "complete".asJson)
  private def errorMsg(id: String, msg: String) =
    Json.obj(
      "id" -> id.asJson,
      "type" -> "error".asJson,
      "payload" -> Json.arr(Json.obj("message" -> msg.asJson)))

  def handler(
      wsb: WebSocketBuilder2[IO],
      mapping: Mapping[IO],
      logger: Logger[IO]): IO[Response[IO]] =
    for {
      out <- Queue.unbounded[IO, WebSocketFrame]
      subs <- IO.ref(Map.empty[String, Fiber[IO, Throwable, Unit]])
      // A periodic Ping keeps the connection alive past Ember's idle timeout while a subscription
      // waits between (possibly rare) events; the client auto-replies Pong, filtered out below.
      heartbeat = Stream.awakeEvery[IO](20.seconds).as(WebSocketFrame.Ping())
      resp <- wsb.build(
        Stream.fromQueueUnterminated(out).merge(heartbeat),
        _.foreach(frame => handle(mapping, logger, out, subs, frame))
          .onFinalize(cancelAll(subs))
      )
    } yield resp

  private def cancelAll(subs: Subs): IO[Unit] =
    subs.getAndSet(Map.empty).flatMap(_.values.toList.traverse_(_.cancel))

  private def handle(
      mapping: Mapping[IO],
      logger: Logger[IO],
      out: Queue[IO, WebSocketFrame],
      subs: Subs,
      frame: WebSocketFrame): IO[Unit] =
    frame match {
      case t: WebSocketFrame.Text =>
        parser.parse(t.str).toOption.flatMap(_.asObject) match {
          case None => IO.unit
          case Some(obj) =>
            obj("type").flatMap(_.asString) match {
              case Some("connection_init") => out.offer(text(ackMsg))
              case Some("ping") => out.offer(text(pongMsg))
              case Some("subscribe") => startSub(mapping, logger, out, subs, obj)
              case Some("complete") => obj("id").flatMap(_.asString).traverse_(stopSub(subs, _))
              case _ => IO.unit // pong, connection ack from peer, etc.
            }
        }
      case _ => IO.unit
    }

  private def startSub(
      mapping: Mapping[IO],
      logger: Logger[IO],
      out: Queue[IO, WebSocketFrame],
      subs: Subs,
      obj: JsonObject): IO[Unit] =
    (obj("id").flatMap(_.asString), obj("payload").flatMap(_.asObject)) match {
      case (Some(id), Some(payload)) =>
        payload("query").flatMap(_.asString) match {
          case None => out.offer(text(errorMsg(id, "Missing query")))
          case Some(query) =>
            val op = payload("operationName").flatMap(_.asString)
            val vars = payload("variables")
            val run =
              mapping
                .compileAndRunSubscription(query, op, vars)
                .evalMap(json => out.offer(text(nextMsg(id, json))))
                .compile
                .drain
                .attempt
                .flatMap {
                  case Right(_) => out.offer(text(completeMsg(id)))
                  case Left(e) =>
                    logger.warn(e)(s"WS subscription $id failed") *>
                      out.offer(text(errorMsg(id, Option(e.getMessage).getOrElse(e.toString))))
                }
            run.start.flatMap(fib => subs.update(_.updated(id, fib)))
        }
      case _ => IO.unit
    }

  private def stopSub(subs: Subs, id: String): IO[Unit] =
    subs.modify(m => (m - id, m.get(id))).flatMap(_.traverse_(_.cancel))
}
