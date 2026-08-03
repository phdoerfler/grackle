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

import jakarta.persistence.{EntityGraph, EntityManager}

/**
 * For `shape.tuned` shapes, builds a per-shape `EntityGraph` reaching exactly `shape.depth`
 * hops and passes it as a `jakarta.persistence.fetchgraph` hint on the root lookup, so Hibernate
 * fetch-joins the whole chain in as few statements as its planner can manage. All associations
 * in `AdventureWorksEntities` are `java.util.Set` (not `List`) specifically so this can traverse
 * multiple nested collection levels in one entity graph without Hibernate's
 * `MultipleBagFetchException` (which applies only to `List`-typed collections).
 *
 * For `!shape.tuned` (the `untuned` shape), no entity graph is built at all — the lookup is a
 * plain `find`, identical to `NaiveOrmArm`, so this arm's "tuning" degrades exactly to the
 * blanket `@BatchSize` default the spec calls for.
 */
object EagerOrmArm {

  private val hopEntityClass: Map[String, Class[_]] =
    Map(
      "stateProvinces" -> classOf[StateProvinceEntity],
      "addresses" -> classOf[AddressEntity],
      "businessEntityAddresses" -> classOf[BusinessEntityAddressEntity],
      "person" -> classOf[PersonEntity],
      "customers" -> classOf[CustomerEntity],
      "salesOrders" -> classOf[SalesOrderHeaderEntity],
      "lineItems" -> classOf[SalesOrderDetailEntity],
      "product" -> classOf[ProductEntity],
      "subcategory" -> classOf[ProductSubcategoryEntity],
      "category" -> classOf[ProductCategoryEntity]
    )

  private def buildEntityGraph(em: EntityManager, depth: Int): EntityGraph[CountryRegionEntity] = {
    val graph = em.createEntityGraph(classOf[CountryRegionEntity])
    val hops = grackle.benchmarks.sql.JoinChain.hops.take(depth)

    def attach(addSubgraph: (String, Class[_]) => jakarta.persistence.Subgraph[_], remaining: List[String]): Unit =
      remaining match {
        case Nil => ()
        case hop :: rest =>
          val sub = addSubgraph(hop, hopEntityClass(hop))
          attach((name, cls) => sub.addSubgraph(name, cls), rest)
      }

    attach((name, cls) => graph.addSubgraph(name, cls), hops)
    graph
  }

  def run(em: EntityManager, shape: Shape, rootCode: String): List[String] = {
    val root =
      if (!shape.tuned) em.find(classOf[CountryRegionEntity], rootCode)
      else {
        import scala.jdk.CollectionConverters._
        val graph = buildEntityGraph(em, shape.depth)
        val hints: java.util.Map[String, Object] =
          Map[String, Object]("jakarta.persistence.fetchgraph" -> graph).asJava
        try em.find(classOf[CountryRegionEntity], rootCode, hints)
        catch {
          // The same dangling-FK data quirk `NaiveOrmArm` documents on `businessEntityAddress
          // .person` (a live businessentityaddress row whose businessentityid belongs to a
          // Store/Vendor, not a Person) surfaces differently here: NaiveOrmArm never eagerly
          // resolves that association, so Hibernate only notices the missing target row (and
          // throws `EntityNotFoundException`, already caught there) on first dereference of the
          // lazy proxy. Here the fetchgraph hint forces Hibernate to LEFT JOIN into `person`
          // as part of this single query, and it throws `FetchNotFoundException` synchronously
          // from `find` the moment it reads a row whose joined person columns are absent for a
          // non-null businessentityid — before any application code runs. This is unavoidable
          // for any tuned shape whose depth reaches the `person` hop (`deepNarrow`, `deepWide`;
          // `shallowNarrow`'s depth 3 stops one hop short of `person`), and it is deterministic
          // for a fixed root code, not a rare flake: `defaultRootCode` ("FR") hits it every time
          // via businessentityid 434.
          //
          // Adding `@NotFound(action = NotFoundAction.IGNORE)` to that mapping was considered
          // and rejected: Hibernate requires `@NotFound` associations to always be fetched
          // eagerly, so annotating the shared entity class would silently turn every
          // `businessEntityAddress.person` access eager everywhere — including inside
          // `NaiveOrmArm`, which exists specifically to demonstrate plain lazy loading. That
          // would corrupt the naive arm's own contract to fix this one. So the recovery is
          // local to this arm: catch the exception, `clear()` the persistence context (a plain
          // `find` retry without `clear()` just returns the same partially-hydrated, cached
          // entity — its collections never got initialized before the eager load aborted, so
          // the retry NPEs instead of re-querying), and fall through to a plain `find` plus
          // `NaiveOrmArm`'s own already-correct lazy walk (with its own catch on this exact
          // hop) — i.e. for this one shape+root combination, the eager arm degrades to the
          // same behavior as the untuned arm, rather than crashing.
          case _: org.hibernate.FetchNotFoundException =>
            em.clear()
            em.find(classOf[CountryRegionEntity], rootCode)
        }
      }
    if (root == null) Nil
    else NaiveOrmArm.run(em, shape, rootCode) // same walk; the graph already preloaded everything above
  }
}
