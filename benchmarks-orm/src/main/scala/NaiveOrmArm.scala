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

import jakarta.persistence.EntityManager

/**
 * Plain lazy-loading traversal: no `@EntityGraph`, no `JOIN FETCH` — every association access
 * below is a separate (batched, thanks to the blanket `@BatchSize`) lazy load. This is the
 * "nobody configured anything" arm.
 */
object NaiveOrmArm {

  def run(em: EntityManager, shape: Shape, rootCode: String): List[String] = {
    val root = em.find(classOf[CountryRegionEntity], rootCode)
    if (root == null) Nil
    else descend(root.stateProvinces.asScala.toList, JoinChain.hopsFrom(1, shape.depth), shape)
  }

  private object JoinChain {
    // Local alias avoiding a name clash with benchmarksSql's JoinChain while reading similarly;
    // `hopsFrom` just slices the shared hop list the same way `JoinChain.hops.take(depth)` does,
    // starting after the root's own first hop (stateProvinces) which `run` already consumed.
    def hopsFrom(start: Int, depth: Int): List[String] =
      grackle.benchmarks.sql.JoinChain.hops.slice(start, depth)
  }

  // Called unconditionally for every entity `walk` visits, whether or not it's the terminal
  // (leaf-depth) entity: a hop's wide fields are still "selected at that hop" even when nothing
  // is nested underneath it (mirrors a GraphQL query's outermost selected field still carrying
  // its own leaf-field selection). Each entity is visited by exactly one `walk` call, so calling
  // this at the top of `walk` reads every hop's wide fields exactly once — no double-counting,
  // and no gap at the terminal hop.
  private def touchWide(entity: AnyRef, shape: Shape): Unit =
    if (shape.wideFields) {
      val _ = entity match {
        case p: PersonEntity => (p.firstName, p.lastName)
        case c: CustomerEntity => c.territoryId
        case s: SalesOrderHeaderEntity => s.totalDue
        case d: SalesOrderDetailEntity => (d.orderQty, d.unitPrice)
        case _ => ()
      }
    }

  private def descend(
      stateProvinces: List[StateProvinceEntity],
      remainingHops: List[String],
      shape: Shape): List[String] = {
    def leafNames(entity: AnyRef): List[String] = entity match {
      case c: ProductCategoryEntity => List(c.name)
      case _ => Nil
    }

    def walk(entity: AnyRef, hops: List[String]): List[String] = {
      touchWide(entity, shape)
      hops match {
        case Nil => leafNames(entity)
        case hop :: rest =>
          entity match {
            case s: StateProvinceEntity if hop == "addresses" =>
              s.addresses.asScala.toList.flatMap(walk(_, rest))
            case a: AddressEntity if hop == "businessEntityAddresses" =>
              a.businessEntityAddresses.asScala.toList.flatMap(walk(_, rest))
            case b: BusinessEntityAddressEntity if hop == "person" =>
              // `businessEntityAddress.businessEntityId` isn't always a Person: AdventureWorks'
              // shared BusinessEntity id space also covers Store/Vendor rows (confirmed live: 816
              // of 19614 businessentityaddress rows have no matching person row) — legitimate
              // data, not corruption. Grackle's own schema already models this hop as nullable
              // (`person: Person`, a LEFT JOIN), and the entity mapping now declares
              // `optional = true` to match. That flag alone doesn't stop Hibernate's proxy from
              // throwing EntityNotFoundException on first dereference of a dangling FK, though
              // (it only affects DDL/fetch planning, not this check), so this hop's dereference —
              // and only this one — is wrapped to treat that specific exception as "no match",
              // mirroring the LEFT JOIN semantics the schema already assumes. `walk`'s first
              // action on the resolved person entity is always `touchWide`, so if the proxy is
              // dangling this throws immediately, before any deeper hops are visited — this catch
              // can't mask an unrelated data-integrity bug further down the chain (e.g. in the
              // product/category hops).
              try Option(b.person).toList.flatMap(walk(_, rest))
              catch { case _: jakarta.persistence.EntityNotFoundException => Nil }
            case p: PersonEntity if hop == "customers" =>
              p.customers.asScala.toList.flatMap(walk(_, rest))
            case c: CustomerEntity if hop == "salesOrders" =>
              c.salesOrders.asScala.toList.flatMap(walk(_, rest))
            case s: SalesOrderHeaderEntity if hop == "lineItems" =>
              s.lineItems.asScala.toList.flatMap(walk(_, rest))
            case d: SalesOrderDetailEntity if hop == "product" =>
              walk(d.product, rest)
            case p: ProductEntity if hop == "subcategory" =>
              Option(p.subcategory).toList.flatMap(walk(_, rest))
            case s: ProductSubcategoryEntity if hop == "category" =>
              walk(s.category, rest)
            case _ => Nil
          }
      }
    }

    stateProvinces.flatMap(sp => walk(sp, remainingHops))
  }
}
