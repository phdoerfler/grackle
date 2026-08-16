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

import java.{util => ju}

import jakarta.persistence._
import org.hibernate.annotations.{BatchSize, NotFound, NotFoundAction}

@Entity
@Table(name = "countryregion", schema = "person")
class CountryRegionEntity {
  @Id
  @Column(name = "countryregioncode")
  var countryRegionCode: String = _

  @Column(name = "name")
  var name: String = _

  @OneToMany(mappedBy = "countryRegion", fetch = FetchType.LAZY)
  @BatchSize(size = 32)
  var stateProvinces: ju.Set[StateProvinceEntity] = _
}

@Entity
@Table(name = "stateprovince", schema = "person")
class StateProvinceEntity {
  @Id
  @Column(name = "stateprovinceid")
  var id: Integer = _

  @Column(name = "name")
  var name: String = _

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "countryregioncode", insertable = false, updatable = false)
  var countryRegion: CountryRegionEntity = _

  @OneToMany(mappedBy = "stateProvince", fetch = FetchType.LAZY)
  @BatchSize(size = 32)
  var addresses: ju.Set[AddressEntity] = _
}

@Entity
@Table(name = "address", schema = "person")
class AddressEntity {
  @Id
  @Column(name = "addressid")
  var id: Integer = _

  @Column(name = "city")
  var city: String = _

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "stateprovinceid", insertable = false, updatable = false)
  var stateProvince: StateProvinceEntity = _

  @OneToMany(mappedBy = "address", fetch = FetchType.LAZY)
  @BatchSize(size = 32)
  var businessEntityAddresses: ju.Set[BusinessEntityAddressEntity] = _
}

/**
 * True Postgres primary key for `person.businessentityaddress` is composite
 * `(businessentityid, addressid, addresstypeid)` — confirmed against the pinned upstream
 * `install.sql` (`PK_BusinessEntityAddress_BusinessEntityID_AddressID_AddressType`), unlike
 * every other table in this chain, which has a single-column key. Grackle's own mapping only
 * needs `businessEntityId` as a GraphQL cursor key (`TableDef.key`), which doesn't have to be a
 * true SQL primary key — JPA's `@Id` does.
 */
@Embeddable
class BusinessEntityAddressId extends Serializable {
  @Column(name = "businessentityid")
  var businessEntityId: Integer = _

  @Column(name = "addressid")
  var addressId: Integer = _

  @Column(name = "addresstypeid")
  var addressTypeId: Integer = _

  override def equals(other: Any): Boolean = other match {
    case o: BusinessEntityAddressId =>
      businessEntityId == o.businessEntityId &&
      addressId == o.addressId &&
      addressTypeId == o.addressTypeId
    case _ => false
  }

  override def hashCode(): Int = (businessEntityId, addressId, addressTypeId).##
}

@Entity
@Table(name = "businessentityaddress", schema = "person")
class BusinessEntityAddressEntity {
  @EmbeddedId
  var id: BusinessEntityAddressId = _

  @Column(name = "addresstypeid", insertable = false, updatable = false)
  var addressTypeId: Integer = _

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId("addressId")
  @JoinColumn(name = "addressid")
  var address: AddressEntity = _

  // Matches Grackle's own mapping (`Join(businessEntityAddress.businessEntityId,
  // person.businessEntityId)`), which joins directly to `person`, skipping the unused
  // `BusinessEntity` supertype table AdventureWorks defines but this chain never queries.
  //
  // `optional = true`: AdventureWorks shares one BusinessEntity id space across Person, Store,
  // and Vendor, and 816 of 19614 businessentityaddress rows (live-verified) have a
  // businessentityid that belongs to a Store/Vendor, not a Person — legitimate data, not
  // corruption. Grackle's own GraphQL schema already models this hop as nullable
  // (`person: Person`, no `!`); `optional = false` here would assert a cardinality guarantee
  // neither the schema nor the data actually has.
  //
  // `@NotFound(action = NotFoundAction.IGNORE)`: there's no actual FK constraint backing this
  // column (live-verified: every one of the 6 full-chain root codes — US/AU/CA/GB/DE/FR — has
  // businessentityaddress rows whose businessentityid dangles, 40-541 rows depending on root;
  // this is structural to the AdventureWorks-for-Postgres dataset, not an artifact of any one
  // root code), so this is exactly the referential-integrity-violation-without-a-constraint
  // case `@NotFound` exists for: Hibernate treats a dangling id as if the FK were null instead
  // of throwing. Per `@NotFound`'s own javadoc, it forces the association eager regardless of
  // the declared `fetch`, so `FetchType.EAGER` is spelled out explicitly here rather than left
  // as `LAZY` (which Hibernate would silently override anyway) — this is a real, unavoidable
  // Hibernate constraint for this schema, not a benchmark-fairness compromise: both the naive
  // and eager arms pay this identical one-hop cost identically, and no other hop is affected.
  @ManyToOne(fetch = FetchType.EAGER, optional = true)
  @NotFound(action = NotFoundAction.IGNORE)
  @MapsId("businessEntityId")
  @JoinColumn(name = "businessentityid")
  var person: PersonEntity = _
}

@Entity
@Table(name = "person", schema = "person")
class PersonEntity {
  @Id
  @Column(name = "businessentityid")
  var businessEntityId: Integer = _

  @Column(name = "firstname")
  var firstName: String = _

  @Column(name = "lastname")
  var lastName: String = _

  @OneToMany(mappedBy = "person", fetch = FetchType.LAZY)
  @BatchSize(size = 32)
  var customers: ju.Set[CustomerEntity] = _
}

/**
 * A flat Person mapped to the `person.person_heavy` view, carrying the deliberately expensive
 * `heavy` and byte-wide `wide` columns. Kept SEPARATE from `PersonEntity` on purpose: Hibernate
 * selects every mapped scalar when it loads an entity, so if the chain's `PersonEntity` carried
 * these columns, every ORM benchmark that reaches Person — including the latency sweep — would
 * pay for them, conflating over-fetch cost with round-trip cost. Only `OverfetchTiming` uses
 * this entity, so the over-fetch cost stays confined to the over-fetch demo. See
 * `testdata/benchmark-pg/20-heavy-column.sql`.
 */
@Entity
@Table(name = "person_heavy", schema = "person")
class PersonHeavyEntity {
  @Id
  @Column(name = "businessentityid")
  var businessEntityId: Integer = _

  @Column(name = "firstname")
  var firstName: String = _

  @Column(name = "lastname")
  var lastName: String = _

  @Column(name = "heavy")
  var heavy: Integer = _

  @Column(name = "wide")
  var wide: String = _
}

@Entity
@Table(name = "customer", schema = "sales")
class CustomerEntity {
  @Id
  @Column(name = "customerid")
  var id: Integer = _

  @Column(name = "territoryid")
  var territoryId: Integer = _

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "personid", insertable = false, updatable = false)
  var person: PersonEntity = _

  @OneToMany(mappedBy = "customer", fetch = FetchType.LAZY)
  @BatchSize(size = 32)
  var salesOrders: ju.Set[SalesOrderHeaderEntity] = _
}

@Entity
@Table(name = "salesorderheader", schema = "sales")
class SalesOrderHeaderEntity {
  @Id
  @Column(name = "salesorderid")
  var id: Integer = _

  @Column(name = "totaldue")
  var totalDue: java.math.BigDecimal = _

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "customerid", insertable = false, updatable = false)
  var customer: CustomerEntity = _

  @OneToMany(mappedBy = "salesOrderHeader", fetch = FetchType.LAZY)
  @BatchSize(size = 32)
  var lineItems: ju.Set[SalesOrderDetailEntity] = _
}

@Entity
@Table(name = "salesorderdetail", schema = "sales")
class SalesOrderDetailEntity {
  // `salesorderdetailid` is a globally-unique SERIAL column, even though the table's SQL
  // primary key is composite `(salesorderid, salesorderdetailid)` — same pragmatic choice
  // Grackle's own mapping makes (`salesOrderDetail.id`, `key = true`, single column).
  @Id
  @Column(name = "salesorderdetailid")
  var id: Integer = _

  @Column(name = "orderqty")
  var orderQty: Integer = _

  @Column(name = "unitprice")
  var unitPrice: java.math.BigDecimal = _

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "salesorderid", insertable = false, updatable = false)
  var salesOrderHeader: SalesOrderHeaderEntity = _

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "productid")
  var product: ProductEntity = _
}

@Entity
@Table(name = "product", schema = "production")
@BatchSize(size = 32)
class ProductEntity {
  @Id
  @Column(name = "productid")
  var id: Integer = _

  @Column(name = "name")
  var name: String = _

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "productsubcategoryid")
  var subcategory: ProductSubcategoryEntity = _
}

@Entity
@Table(name = "productsubcategory", schema = "production")
@BatchSize(size = 32)
class ProductSubcategoryEntity {
  @Id
  @Column(name = "productsubcategoryid")
  var id: Integer = _

  @Column(name = "name")
  var name: String = _

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "productcategoryid")
  var category: ProductCategoryEntity = _
}

@Entity
@Table(name = "productcategory", schema = "production")
@BatchSize(size = 32)
class ProductCategoryEntity {
  @Id
  @Column(name = "productcategoryid")
  var id: Integer = _

  @Column(name = "name")
  var name: String = _
}
