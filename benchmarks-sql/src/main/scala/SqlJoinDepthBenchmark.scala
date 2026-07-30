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

import java.util.concurrent.TimeUnit

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import grackle.Mapping

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class SqlJoinDepthBenchmark {

  @Param(Array("2", "4", "6", "8", "10"))
  var depth: Int = _

  var mapping: Mapping[IO] = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    mapping = AdventureWorksMapping.mkMapping[IO](BenchmarkDb.transactor[IO])
  }

  @Benchmark
  def runJoinDepthQuery(blackhole: Blackhole): Unit = {
    val query = JoinChain.queryForDepth(depth)
    val result = mapping.compileAndRun(query).unsafeRunSync()
    blackhole.consume(result)
  }
}
