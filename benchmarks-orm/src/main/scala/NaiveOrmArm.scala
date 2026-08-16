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

import scala.jdk.CollectionConverters._

import io.circe.Json
import jakarta.persistence.EntityManager

/**
 * Plain lazy-loading traversal: no `@EntityGraph`, no `JOIN FETCH` — every association access
 * below is a separate (batched, thanks to the blanket `@BatchSize`) lazy load. This is the
 * "nobody configured anything" arm.
 */
object NaiveOrmArm {

  def run(em: EntityManager, shape: Shape, rootCode: String): Json = {
    val root = em.find(classOf[CountryRegionEntity], rootCode)
    val regions =
      if (root == null) Vector.empty[Json]
      else
        descend(root.stateProvinces.asScala.toList, JoinChain.hopsFrom(1, shape.depth), shape)
          .map(sps =>
            Json.obj(
              "countryRegionCode" -> Json.fromString(root.countryRegionCode),
              firstHop(shape) -> sps))
          .toVector
    Json.obj("data" -> Json.obj("countryRegions" -> Json.arr(regions: _*)))
  }

  // The root's own hop, consumed by `run` before `descend` takes over.
  private def firstHop(shape: Shape): String = Selection.hopsFor(shape).head

  private object JoinChain {
    // Local alias avoiding a name clash with benchmarksSql's JoinChain while reading similarly;
    // `hopsFrom` just slices the shared hop list the same way `JoinChain.hops.take(depth)` does,
    // starting after the root's own first hop (stateProvinces) which `run` already consumed.
    def hopsFrom(start: Int, depth: Int): List[String] =
      grackle.benchmarks.sql.JoinChain.hops.slice(start, depth)
  }

  private def descend(
      stateProvinces: List[StateProvinceEntity],
      remainingHops: List[String],
      shape: Shape): Option[Json] = {
    val terminalHop = Selection.hopsFor(shape).last

    def walk(entity: AnyRef, hop: String, hops: List[String]): Option[Json] = {
      def scalars: List[(String, Json)] = OrmJson.scalarsFor(
        entity = entity,
        hop = hop,
        shape = shape,
        isTerminal = hop == terminalHop)
      hops match {
        case Nil => Some(Json.obj(scalars: _*))
        case next :: rest =>
          val child: Option[Json] =
            entity match {
              case s: StateProvinceEntity if next == "addresses" =>
                jsonArr(s.addresses.asScala.toList, next, rest)
              case a: AddressEntity if next == "businessEntityAddresses" =>
                jsonArr(a.businessEntityAddresses.asScala.toList, next, rest)
              case b: BusinessEntityAddressEntity if next == "person" =>
                // `businessEntityAddress.businessEntityId` isn't always a Person: AdventureWorks'
                // shared BusinessEntity id space also covers Store/Vendor rows (confirmed live:
                // 816 of 19614 rows have no matching person). `person: Person` is nullable, so
                // Grackle LEFT JOINs it and emits `"person": null`. Since #888 a non-null to-one
                // lying further down no longer eliminates that null-padded row — the subordinate
                // INNER JOIN can't drop a row its parent LEFT JOIN chose to keep. `jsonNullable`
                // encodes exactly that.
                jsonNullable(Option(b.person), next, rest)
              case p: PersonEntity if next == "customers" =>
                jsonArr(p.customers.asScala.toList, next, rest)
              case c: CustomerEntity if next == "salesOrders" =>
                jsonArr(c.salesOrders.asScala.toList, next, rest)
              case s: SalesOrderHeaderEntity if next == "lineItems" =>
                jsonArr(s.lineItems.asScala.toList, next, rest)
              case d: SalesOrderDetailEntity if next == "product" =>
                jsonRequired(Option(d.product), next, rest)
              case p: ProductEntity if next == "subcategory" =>
                jsonNullable(Option(p.subcategory), next, rest)
              case s: ProductSubcategoryEntity if next == "category" =>
                jsonRequired(Option(s.category), next, rest)
              // Reachable only if `JoinChain.hops`, an entity's `@OneToMany(mappedBy=...)` string,
              // or `EagerOrmArm.hopEntityClass`'s keys ever drift out of sync with each other.
              // Fail loudly rather than silently truncating the walk, matching every other
              // string-keyed lookup in this codebase.
              case _ =>
                throw new IllegalStateException(
                  s"no traversal defined for hop '$next' from entity " +
                    entity.getClass.getSimpleName)
            }
          child.map(c => Json.obj(scalars :+ (next -> c): _*))
      }
    }

    // A to-many hop, assembled by Grackle with a LEFT JOIN: dead children are dropped, but an empty
    // result is a legitimate `[]`, never a reason to eliminate this node. Since #888 (a non-null
    // field under a nullable parent no longer drops the null-padded row), a non-null to-one lying
    // below no longer eliminates a childless collection either.
    def jsonArr(entities: List[AnyRef], hop: String, rest: List[String]): Option[Json] = {
      val kids = entities.flatMap(e => walk(e, hop, rest))
      Some(Json.arr(kids: _*))
    }

    // A nullable to-one hop, assembled by Grackle with a LEFT JOIN: absent or dead becomes `null`.
    // A non-null to-one lying below does not eliminate the row (see #888) — its own INNER JOIN is
    // subordinate to this LEFT JOIN, so the null-padded parent survives with `null` here.
    def jsonNullable(entity: Option[AnyRef], hop: String, rest: List[String]): Option[Json] =
      entity.flatMap(e => walk(e, hop, rest)).orElse(Some(Json.Null))

    // A non-null to-one hop: no row can exist without it, so absent or dead kills the branch.
    def jsonRequired(entity: Option[AnyRef], hop: String, rest: List[String]): Option[Json] =
      entity.flatMap(e => walk(e, hop, rest))

    jsonArr(stateProvinces, firstHop(shape), remainingHops)
  }
}
