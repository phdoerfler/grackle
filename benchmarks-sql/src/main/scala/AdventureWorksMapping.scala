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

import cats.effect.Sync
import org.typelevel.doobie.util.meta.Meta
import org.typelevel.doobie.util.transactor.Transactor

import grackle._
import grackle.Predicate._
import grackle.Query._
import grackle.QueryCompiler._
import grackle.Value._
import grackle.doobie.{DoobieMappingCompanion, DoobieMonitor}
import grackle.doobie.postgres.DoobiePgMapping
import grackle.syntax._

trait AdventureWorksSchema[F[_]] extends DoobiePgMapping[F] {

  object countryRegion extends TableDef("person.countryregion") {
    val code = col("countryregioncode", Meta[String])
    val name = col("name", Meta[String])
  }

  object stateProvince extends TableDef("person.stateprovince") {
    val id = col("stateprovinceid", Meta[Int])
    val countryRegionCode = col("countryregioncode", Meta[String])
    val name = col("name", Meta[String])
  }

  object address extends TableDef("person.address") {
    val id = col("addressid", Meta[Int])
    val stateProvinceId = col("stateprovinceid", Meta[Int])
    val city = col("city", Meta[String])
  }

  object businessEntityAddress extends TableDef("person.businessentityaddress") {
    val businessEntityId = col("businessentityid", Meta[Int])
    val addressId = col("addressid", Meta[Int])
    val addressTypeId = col("addresstypeid", Meta[Int])
  }

  object person extends TableDef("person.person") {
    val businessEntityId = col("businessentityid", Meta[Int])
    val firstName = col("firstname", Meta[String])
    val lastName = col("lastname", Meta[String])
  }

  object customer extends TableDef("sales.customer") {
    val id = col("customerid", Meta[Int])
    val personId = col("personid", Meta[Int], true)
    val territoryId = col("territoryid", Meta[Int], true)
  }

  object salesOrderHeader extends TableDef("sales.salesorderheader") {
    val id = col("salesorderid", Meta[Int])
    val customerId = col("customerid", Meta[Int])
    val totalDue = col("totaldue", Meta[BigDecimal], true)
  }

  object salesOrderDetail extends TableDef("sales.salesorderdetail") {
    val id = col("salesorderdetailid", Meta[Int])
    val salesOrderId = col("salesorderid", Meta[Int])
    val productId = col("productid", Meta[Int])
    val orderQty = col("orderqty", Meta[Int])
    val unitPrice = col("unitprice", Meta[BigDecimal])
  }

  object product extends TableDef("production.product") {
    val id = col("productid", Meta[Int])
    val name = col("name", Meta[String])
    val subcategoryId = col("productsubcategoryid", Meta[Int], true)
  }

  object productSubcategory extends TableDef("production.productsubcategory") {
    val id = col("productsubcategoryid", Meta[Int])
    val categoryId = col("productcategoryid", Meta[Int])
    val name = col("name", Meta[String])
  }

  object productCategory extends TableDef("production.productcategory") {
    val id = col("productcategoryid", Meta[Int])
    val name = col("name", Meta[String])
  }
}

trait AdventureWorksMapping[F[_]] extends AdventureWorksSchema[F] {

  val schema =
    schema"""
      type Query {
        countryRegions(code: String): [CountryRegion!]!
      }
      type CountryRegion {
        countryRegionCode: String!
        name: String!
        stateProvinces: [StateProvince!]!
      }
      type StateProvince {
        name: String!
        addresses: [Address!]!
      }
      type Address {
        city: String!
        businessEntityAddresses: [BusinessEntityAddress!]!
      }
      type BusinessEntityAddress {
        addressTypeId: Int!
        person: Person
      }
      type Person {
        firstName: String!
        lastName: String!
        customers: [Customer!]!
      }
      type Customer {
        territoryId: Int
        salesOrders: [SalesOrderHeader!]!
      }
      type SalesOrderHeader {
        totalDue: Float
        lineItems: [SalesOrderDetail!]!
      }
      type SalesOrderDetail {
        orderQty: Int!
        unitPrice: Float!
        product: Product!
      }
      type Product {
        name: String!
        subcategory: ProductSubcategory
      }
      type ProductSubcategory {
        name: String!
        category: ProductCategory!
      }
      type ProductCategory {
        name: String!
      }
    """

  val QueryType = schema.ref("Query")
  val CountryRegionType = schema.ref("CountryRegion")
  val StateProvinceType = schema.ref("StateProvince")
  val AddressType = schema.ref("Address")
  val BusinessEntityAddressType = schema.ref("BusinessEntityAddress")
  val PersonType = schema.ref("Person")
  val CustomerType = schema.ref("Customer")
  val SalesOrderHeaderType = schema.ref("SalesOrderHeader")
  val SalesOrderDetailType = schema.ref("SalesOrderDetail")
  val ProductType = schema.ref("Product")
  val ProductSubcategoryType = schema.ref("ProductSubcategory")
  val ProductCategoryType = schema.ref("ProductCategory")

  val typeMappings =
    TypeMappings(
      ObjectMapping(QueryType)(
        SqlObject("countryRegions")
      ),
      ObjectMapping(CountryRegionType)(
        SqlField("countryRegionCode", countryRegion.code, key = true),
        SqlField("name", countryRegion.name),
        SqlObject("stateProvinces", Join(countryRegion.code, stateProvince.countryRegionCode))
      ),
      ObjectMapping(StateProvinceType)(
        SqlField("id", stateProvince.id, key = true, hidden = true),
        SqlField("name", stateProvince.name),
        SqlObject("addresses", Join(stateProvince.id, address.stateProvinceId))
      ),
      ObjectMapping(AddressType)(
        SqlField("id", address.id, key = true, hidden = true),
        SqlField("city", address.city),
        SqlObject("businessEntityAddresses", Join(address.id, businessEntityAddress.addressId))
      ),
      ObjectMapping(BusinessEntityAddressType)(
        SqlField(
          "businessEntityId",
          businessEntityAddress.businessEntityId,
          key = true,
          hidden = true),
        SqlField("addressId", businessEntityAddress.addressId, hidden = true),
        SqlField("addressTypeId", businessEntityAddress.addressTypeId),
        SqlObject(
          "person",
          Join(businessEntityAddress.businessEntityId, person.businessEntityId))
      ),
      ObjectMapping(PersonType)(
        SqlField("businessEntityId", person.businessEntityId, key = true, hidden = true),
        SqlField("firstName", person.firstName),
        SqlField("lastName", person.lastName),
        SqlObject("customers", Join(person.businessEntityId, customer.personId))
      ),
      ObjectMapping(CustomerType)(
        SqlField("id", customer.id, key = true, hidden = true),
        SqlField("territoryId", customer.territoryId),
        SqlObject("salesOrders", Join(customer.id, salesOrderHeader.customerId))
      ),
      ObjectMapping(SalesOrderHeaderType)(
        SqlField("id", salesOrderHeader.id, key = true, hidden = true),
        SqlField("totalDue", salesOrderHeader.totalDue),
        SqlObject("lineItems", Join(salesOrderHeader.id, salesOrderDetail.salesOrderId))
      ),
      ObjectMapping(SalesOrderDetailType)(
        SqlField("id", salesOrderDetail.id, key = true, hidden = true),
        SqlField("orderQty", salesOrderDetail.orderQty),
        SqlField("unitPrice", salesOrderDetail.unitPrice),
        SqlObject("product", Join(salesOrderDetail.productId, product.id))
      ),
      ObjectMapping(ProductType)(
        SqlField("id", product.id, key = true, hidden = true),
        SqlField("name", product.name),
        SqlField("subcategoryId", product.subcategoryId, hidden = true),
        SqlObject("subcategory", Join(product.subcategoryId, productSubcategory.id))
      ),
      ObjectMapping(ProductSubcategoryType)(
        SqlField("id", productSubcategory.id, key = true, hidden = true),
        SqlField("name", productSubcategory.name),
        SqlField("categoryId", productSubcategory.categoryId, hidden = true),
        SqlObject("category", Join(productSubcategory.categoryId, productCategory.id))
      ),
      ObjectMapping(ProductCategoryType)(
        SqlField("id", productCategory.id, key = true, hidden = true),
        SqlField("name", productCategory.name)
      )
    )

  override val selectElaborator = SelectElaborator {
    case (QueryType, "countryRegions", List(Binding("code", AbsentValue))) =>
      Elab.unit

    case (QueryType, "countryRegions", List(Binding("code", StringValue(code)))) =>
      Elab.transformChild(child =>
        Filter(Eql(CountryRegionType / "countryRegionCode", Const(code)), child))
  }
}

object AdventureWorksMapping extends DoobieMappingCompanion {
  def mkMapping[F[_]: Sync](transactor: Transactor[F], monitor: DoobieMonitor[F]): Mapping[F] =
    new DoobiePgMapping[F](transactor, monitor) with AdventureWorksMapping[F]
}
