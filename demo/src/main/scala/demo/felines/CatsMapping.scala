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

package demo.felines

import cats.effect.{IO, Resource}
import cats.syntax.all._
import fs2.Stream
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.metrics.Meter.Implicits.noop as metricsNoop
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.trace.Tracer.Implicits.noop as tracerNoop
import org.typelevel.twiddles._
import skunk.Session
import skunk.codec.{all => codec}
import skunk.data.Identifier
import skunk.implicits._

import grackle._
import grackle.Predicate._
import grackle.Query._
import grackle.QueryCompiler._
import grackle.Value._
import grackle.skunk.{SkunkMapping, SkunkMonitor}
import grackle.syntax._

import demo.world.WorldData.PostgresConnectionInfo

trait CatsMapping[F[_]] extends SkunkMapping[F] {

  def listenSession: Session[F]

  object cats extends TableDef("cats") {
    val id = col("id", codec.int4)
    val name = col("name", codec.varchar)
    val status = col("status", codec.varchar)
    val position = col("position", codec.varchar)
    val hairLength = col("hair_length", codec.varchar)
    val updatedAt =
      col("updated_at", codec.timestamp.imap(_.toString)(java.time.LocalDateTime.parse))
  }

  val schema =
    schema"""
      enum CatStatus { ASLEEP AWAKE HUNTING GROOMING }
      enum HairLength { SHORT LONG }

      type Query {
        cat(id: Int!): Cat
        cats: [Cat!]!
      }

      type Mutation {
        updateCat(id: Int!, status: CatStatus, position: String, hairLength: HairLength): Cat!
      }

      type Subscription {
        catUpdated(id: Int!): Cat!
      }

      type Cat {
        id: Int!
        name: String!
        status: CatStatus!
        position: String!
        hairLength: HairLength!
        updatedAt: String!
      }
    """

  val QueryType = schema.ref("Query")
  val MutationType = schema.ref("Mutation")
  val SubscriptionType = schema.ref("Subscription")
  val CatType = schema.ref("Cat")
  val CatStatusType = schema.ref("CatStatus")
  val HairLengthType = schema.ref("HairLength")

  case class UpdateCat(
      id: Int,
      status: Option[String],
      position: Option[String],
      hairLength: Option[String])

  val typeMappings =
    List(
      ObjectMapping(
        tpe = QueryType,
        fieldMappings = List(
          SqlObject("cat"),
          SqlObject("cats")
        )
      ),
      ObjectMapping(
        tpe = MutationType,
        fieldMappings = List(
          RootEffect.computeUnit("updateCat")(env =>
            env.getR[UpdateCat]("updateCat").traverse {
              case UpdateCat(id, status, position, hairLength) =>
                updateCatRow(id, status, position, hairLength)
            })
        )
      ),
      ObjectMapping(
        tpe = CatType,
        fieldMappings = List(
          SqlField("id", cats.id, key = true),
          SqlField("name", cats.name),
          SqlField("status", cats.status),
          SqlField("position", cats.position),
          SqlField("hairLength", cats.hairLength),
          SqlField("updatedAt", cats.updatedAt)
        )
      ),
      ObjectMapping(
        tpe = SubscriptionType,
        fieldMappings = List(
          RootStream("catUpdated") { (query, path, env) =>
            env.getR[Int]("catUpdated_id") match {
              case Result.Success(id) =>
                // Register the LISTEN before doing the initial fetch, not after: `Stream`'s `++`
                // only starts its right-hand side once the left is exhausted, so emitting the
                // initial trigger first would leave a window, between that fetch starting and
                // the LISTEN being registered, where a concurrent NOTIFY is silently missed
                // rather than merely causing a harmless extra re-fetch.
                val triggers: Stream[F, Unit] =
                  Stream
                    .resource(listenSession.channel(catUpdatesChannel).listenR(maxQueued = 16))
                    .flatMap { notifications =>
                      Stream.emit(()) ++ notifications.filter(_.value == id.toString).void
                    }
                triggers.evalMap(_ => defaultRootCursor(query, path.rootTpe, None))
              case other =>
                Stream.emit(other.flatMap(_ =>
                  Result.failure[(Query, Cursor)]("Missing catUpdated_id in environment")))
            }
          }
        )
      ),
      LeafMapping[String](CatStatusType),
      LeafMapping[String](HairLengthType)
    )

  override val selectElaborator = SelectElaborator {
    case (QueryType, "cat", List(Binding("id", IntValue(id)))) =>
      Elab.transformChild(child => Unique(Filter(Eql(CatType / "id", Const(id)), child)))

    case (
          MutationType,
          "updateCat",
          List(
            Binding("id", IntValue(id)),
            Binding("status", statusValue),
            Binding("position", positionValue),
            Binding("hairLength", hairLengthValue))) =>
      // Omitted optional arguments bind as `AbsentValue` (same sentinel WorldMapping.scala
      // matches on for its own optional `namePattern` argument) — anything that isn't the
      // expected shape (`EnumValue`/`StringValue`) collapses to "field not being changed."
      val statusOpt = statusValue match {
        case EnumValue(name) => Some(name)
        case _ => None
      }
      val positionOpt = positionValue match {
        case StringValue(s) => Some(s)
        case _ => None
      }
      val hairLengthOpt = hairLengthValue match {
        case EnumValue(name) => Some(name)
        case _ => None
      }
      for {
        _ <- Elab.env("updateCat", UpdateCat(id, statusOpt, positionOpt, hairLengthOpt))
        _ <- Elab.transformChild(child => Unique(Filter(Eql(CatType / "id", Const(id)), child)))
      } yield ()

    case (SubscriptionType, "catUpdated", List(Binding("id", IntValue(id)))) =>
      for {
        _ <- Elab.env("catUpdated_id", id)
        _ <- Elab.transformChild(child => Unique(Filter(Eql(CatType / "id", Const(id)), child)))
      } yield ()
  }

  private val setStatus =
    sql"UPDATE cats SET status = ${codec.varchar} WHERE id = ${codec.int4}".command
  private val setPosition =
    sql"UPDATE cats SET position = ${codec.varchar} WHERE id = ${codec.int4}".command
  private val setHairLength =
    sql"UPDATE cats SET hair_length = ${codec.varchar} WHERE id = ${codec.int4}".command

  private val catUpdatesChannel: Identifier =
    Identifier.fromString("cat_updates").getOrElse(sys.error("invalid channel identifier"))

  def updateCatRow(
      id: Int,
      status: Option[String],
      position: Option[String],
      hairLength: Option[String]): F[Unit] =
    pool.use { session =>
      session.transaction.use { _ =>
        List(
          status.map(s => session.execute(setStatus)(s *: id *: EmptyTuple).void),
          position.map(p => session.execute(setPosition)(p *: id *: EmptyTuple).void),
          hairLength.map(h => session.execute(setHairLength)(h *: id *: EmptyTuple).void)
        ).flatten.sequence_
      }
    }
}

object CatsMapping {
  def resource: Resource[IO, CatsMapping[IO]] = {
    val connInfo = PostgresConnectionInfo("localhost", PostgresConnectionInfo.DefaultPort)
    implicit val meter: Meter[IO] = metricsNoop[IO]
    implicit val tracer: Tracer[IO] = tracerNoop[IO]
    for {
      poolBorrow <- Session
        .Builder[IO]
        .withHost(connInfo.host)
        .withPort(connInfo.port)
        .withUserAndPassword(connInfo.username, connInfo.password)
        .withDatabase(connInfo.databaseName)
        .pooled(max = 4)
      listen <- Session
        .Builder[IO]
        .withHost(connInfo.host)
        .withPort(connInfo.port)
        .withUserAndPassword(connInfo.username, connInfo.password)
        .withDatabase(connInfo.databaseName)
        .single
    } yield new SkunkMapping[IO](poolBorrow, SkunkMonitor.noopMonitor[IO])
      with CatsMapping[IO] {
      val listenSession = listen
    }
  }
}
