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
        Vector(
          Json.obj(
            "countryRegionCode" -> Json.fromString(root.countryRegionCode),
            firstHop(shape) -> descend(
              root.stateProvinces.asScala.toList,
              JoinChain.hopsFrom(1, shape.depth),
              shape)
          ))
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
      shape: Shape): Json = {
    val terminalHop = Selection.hopsFor(shape).last

    def walk(entity: AnyRef, hop: String, hops: List[String]): Json = {
      val scalars = OrmJson.scalarsFor(
        entity = entity,
        hop = hop,
        shape = shape,
        isTerminal = hop == terminalHop)
      val nested: List[(String, Json)] =
        hops match {
          case Nil => Nil
          case next :: rest =>
            val child: Json =
              entity match {
                case s: StateProvinceEntity if next == "addresses" =>
                  jsonArr(s.addresses.asScala.toList, next, rest)
                case a: AddressEntity if next == "businessEntityAddresses" =>
                  jsonArr(a.businessEntityAddresses.asScala.toList, next, rest)
                case b: BusinessEntityAddressEntity if next == "person" =>
                  // `businessEntityAddress.businessEntityId` isn't always a Person: AdventureWorks'
                  // shared BusinessEntity id space also covers Store/Vendor rows (confirmed live:
                  // 816 of 19614 businessentityaddress rows have no matching person row) —
                  // legitimate data, not corruption. Grackle's schema models this hop as nullable
                  // (`person: Person`, a LEFT JOIN) and emits `"person": null` for those rows, so
                  // this must emit the key with a null value too, never omit it. `@NotFound(IGNORE)`
                  // (see `AdventureWorksEntities.scala`) already resolves the dangling FK to null.
                  jsonOpt(Option(b.person), next, rest)
                case p: PersonEntity if next == "customers" =>
                  jsonArr(p.customers.asScala.toList, next, rest)
                case c: CustomerEntity if next == "salesOrders" =>
                  jsonArr(c.salesOrders.asScala.toList, next, rest)
                case s: SalesOrderHeaderEntity if next == "lineItems" =>
                  jsonArr(s.lineItems.asScala.toList, next, rest)
                case d: SalesOrderDetailEntity if next == "product" =>
                  jsonOpt(Option(d.product), next, rest)
                case p: ProductEntity if next == "subcategory" =>
                  jsonOpt(Option(p.subcategory), next, rest)
                case s: ProductSubcategoryEntity if next == "category" =>
                  jsonOpt(Option(s.category), next, rest)
                // Reachable only if `JoinChain.hops`, an entity's `@OneToMany(mappedBy=...)`
                // string, or `EagerOrmArm.hopEntityClass`'s keys ever drift out of sync with each
                // other. Fail loudly rather than silently truncating the walk, matching every
                // other string-keyed lookup in this codebase.
                case _ =>
                  throw new IllegalStateException(
                    s"no traversal defined for hop '$next' from entity " +
                      entity.getClass.getSimpleName)
              }
            List(next -> child)
        }
      Json.obj(scalars ++ nested: _*)
    }

    def jsonArr(entities: List[AnyRef], hop: String, rest: List[String]): Json =
      Json.arr(entities.map(e => walk(e, hop, rest)): _*)

    def jsonOpt(entity: Option[AnyRef], hop: String, rest: List[String]): Json =
      entity.fold(Json.Null)(e => walk(e, hop, rest))

    jsonArr(stateProvinces, "stateProvinces", remainingHops)
  }
}
