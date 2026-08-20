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

package grackle.sql.test

import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID

import io.circe.Json
import munit.FunSuite

final class CellDecoderSuite extends FunSuite {
  test("scalar decoders") {
    assertEquals(CellDecoder[String].decode("hello"), "hello")
    assertEquals(CellDecoder[Int].decode("42"), 42)
    assertEquals(CellDecoder[Long].decode("9000000000"), 9000000000L)
    assertEquals(CellDecoder[Double].decode("1.5"), 1.5d)
    assertEquals(CellDecoder[Boolean].decode("true"), true)
    assertEquals(
      CellDecoder[UUID].decode("6a7837fc-b463-4d32-b628-0f4b3065cb21"),
      UUID.fromString("6a7837fc-b463-4d32-b628-0f4b3065cb21"))
    assertEquals(CellDecoder[LocalDate].decode("1974-10-07"), LocalDate.of(1974, 10, 7))
    assertEquals(
      CellDecoder[OffsetDateTime].decode("2020-05-22T19:35:00Z"),
      OffsetDateTime.parse("2020-05-22T19:35:00Z"))
    assertEquals(CellDecoder[Json].decode("""{"a":1}"""), Json.obj("a" -> Json.fromInt(1)))
  }

  test("list decoder parses curly form") {
    assertEquals(CellDecoder[List[String]].decode("{drama,comedy}"), List("drama", "comedy"))
    assertEquals(CellDecoder[List[String]].decode("{}"), Nil)
    assertEquals(CellDecoder[List[Int]].decode("{1,2,3}"), List(1, 2, 3))
  }
}
