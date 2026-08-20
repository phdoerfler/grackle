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

import java.time.{Duration, LocalDate, LocalTime, OffsetDateTime}
import java.util.UUID

import io.circe.Json
import io.circe.parser.{parse => parseJson}

trait CellDecoder[T] {
  def decode(cell: String): T
}

object CellDecoder {
  def apply[T](implicit d: CellDecoder[T]): CellDecoder[T] = d
  def from[T](f: String => T): CellDecoder[T] =
    new CellDecoder[T] { def decode(cell: String): T = f(cell) }

  implicit val stringDecoder: CellDecoder[String] = from(identity)
  implicit val intDecoder: CellDecoder[Int] = from(_.toInt)
  implicit val longDecoder: CellDecoder[Long] = from(_.toLong)
  implicit val floatDecoder: CellDecoder[Float] = from(_.toFloat)
  implicit val doubleDecoder: CellDecoder[Double] = from(_.toDouble)
  implicit val bigDecimalDecoder: CellDecoder[BigDecimal] = from(BigDecimal(_))
  implicit val booleanDecoder: CellDecoder[Boolean] = from(_.toBoolean)
  implicit val uuidDecoder: CellDecoder[UUID] = from(UUID.fromString)
  implicit val localDateDecoder: CellDecoder[LocalDate] = from(LocalDate.parse)
  implicit val localTimeDecoder: CellDecoder[LocalTime] = from(LocalTime.parse)
  implicit val offsetDateTimeDecoder: CellDecoder[OffsetDateTime] = from(OffsetDateTime.parse)
  implicit val durationDecoder: CellDecoder[Duration] = from(Duration.parse)
  implicit val jsonDecoder: CellDecoder[Json] =
    from(s =>
      parseJson(s).fold(
        err => throw new IllegalArgumentException(s"bad json: $s", err),
        identity
      ))

  implicit def listDecoder[A](implicit d: CellDecoder[A]): CellDecoder[List[A]] =
    from { cell =>
      val inner = cell.trim.stripPrefix("{").stripSuffix("}")
      if (inner.isEmpty) Nil
      else
        inner.split(",").toList.map { e =>
          // Postgres array text quotes elements that need it (e.g. {"drama","comedy"});
          // strip a single pair of surrounding double quotes before decoding.
          val t = e.trim
          val unquoted =
            if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\""))
              t.substring(1, t.length - 1)
            else t
          d.decode(unquoted)
        }
    }
}
