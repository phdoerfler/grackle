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
 * hops and passes it as a `jakarta.persistence.loadgraph` hint on the root lookup, so Hibernate
 * fetch-joins the whole chain in as few statements as its planner can manage. `loadgraph` (not
 * `fetchgraph`) specifically: see the comment on the hint construction below for why — in
 * short, `businessEntityAddress.person`'s `@NotFound`-forced-eager mapping needs `loadgraph`'s
 * "leave non-graph attributes at their mapped default" semantics, since `fetchgraph`'s "force
 * non-graph attributes lazy" would conflict with `@NotFound`'s "no lazy proxy possible." All
 * associations in `AdventureWorksEntities` are `java.util.Set` (not `List`) specifically so
 * this can traverse multiple nested collection levels in one entity graph without Hibernate's
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

  private def buildEntityGraph(
      em: EntityManager,
      depth: Int): EntityGraph[CountryRegionEntity] = {
    val graph = em.createEntityGraph(classOf[CountryRegionEntity])
    val hops = grackle.benchmarks.sql.JoinChain.hops.take(depth)

    def attach(
        addSubgraph: (String, Class[_]) => jakarta.persistence.Subgraph[_],
        remaining: List[String]): Unit =
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
          Map[String, Object]("jakarta.persistence.loadgraph" -> graph).asJava
        // `loadgraph`, not `fetchgraph`: the two JPA-standard graph hints differ in how they
        // treat attributes NOT explicitly listed in the graph. `fetchgraph` forces them to
        // LAZY, overriding their mapped fetch type; `loadgraph` leaves them at their originally
        // mapped default instead. That distinction matters here because of `person`
        // specifically: `@NotFound` (see `AdventureWorksEntities.scala`) makes that association
        // structurally non-lazy (no proxy class can defer the not-found check), so for any
        // shape whose graph stops short of the `person` hop (e.g. `shallowNarrow`), a
        // `fetchgraph` hint asks Hibernate to do something it can't (treat `person` as lazy),
        // and Hibernate resolved that conflict by falling back to one immediate secondary
        // select per `BusinessEntityAddressEntity` row instead of folding the fetch into the
        // graph's join plan — a real, live-verified N+1 explosion (1852 statements for
        // `shallowNarrow`, confirmed deterministic across repeated runs). `loadgraph` avoids the
        // conflict entirely: since `person` isn't in the graph, it stays at its mapped default
        // (`FetchType.EAGER`, forced by `@NotFound`), so Hibernate applies its normal default
        // eager-fetch strategy for it — a join into whatever query loads the owning row — the
        // same strategy `NaiveOrmArm` already benefits from by default. Associations that ARE
        // explicitly listed in the graph behave identically under both hints; this only changes
        // the one association affected by `@NotFound`'s forced-eager override.
        //
        // The dangling-FK data quirk `NaiveOrmArm` documents on `businessEntityAddress.person`
        // (a live businessentityaddress row whose businessentityid belongs to a Store/Vendor,
        // not a Person) used to force this graph-hinted `find` to throw
        // `FetchNotFoundException` synchronously (Hibernate LEFT JOINing into `person` as part
        // of this single query, then finding no matching row for a non-null businessentityid) —
        // handled here via a catch/clear/retry fallback to a plain, ungraphed `find`. That's no
        // longer needed: the entity mapping's `@NotFound(action = NotFoundAction.IGNORE)` (see
        // `AdventureWorksEntities.scala`) makes Hibernate resolve the dangling row straight to
        // `null` instead of throwing, so the graph-hinted `find` below now succeeds
        // directly, with the entity graph fully applied, for every tuned shape and root code.
        em.find(classOf[CountryRegionEntity], rootCode, hints)
      }
    if (root == null) Nil
    else
      NaiveOrmArm.run(
        em,
        shape,
        rootCode
      ) // same walk; the graph already preloaded everything above
  }
}
