# SQL join-depth benchmarks

Benchmarks how Grackle's compile+execute time scales as GraphQL query nesting
walks deeper down a real 11-table join chain (AdventureWorks-for-Postgres: `CountryRegion
→ StateProvince → Address → BusinessEntityAddress → Person → Customer → SalesOrderHeader
→ SalesOrderDetail → Product → ProductSubcategory → ProductCategory`), against a
dedicated Postgres instance seeded via Docker.

Out of scope for this phase: network-latency simulation, chart publishing, CI wiring.

`benchmarksSql` is a standalone sbt project, not aggregated into the root build (see
`build.sbt`): a plain `sbt test` / `sbt compile` at the repo root will not touch it,
and `benchmark-postgres` is behind the `benchmarks` Docker Compose profile so a plain
`docker compose up` will not build or start it either. Use the `benchmarksSql`-scoped
sbt tasks below instead.

## Running

    sbt benchPgUp                      # starts benchmark-postgres; first run takes
                                        # several minutes to build the image and load
                                        # the AdventureWorks dataset
    sbt "benchmarksSql/Jmh/run -f 1 -wi 3 -w 2s -i 5 -r 2s -rf json -rff results.json"

The explicit flags above ("1 fork", "3 warm-up iterations of 2s", "5 measurement
iterations of 2s") keep a run to a couple of minutes. JMH's own defaults (5 forks x
(5 warmup + 5 measurement) x 10s x 5 `depth` params) take 40+ minutes here, since the
depth-10 operation alone takes hundreds of milliseconds — fine to let run overnight,
but use heavier settings than the ones above for any result you intend to rely on.
See the doc comment on `SqlJoinDepthBenchmark` for more.

Results land in `benchmarks-sql/results.json` (JMH resolves the `-rff` path relative
to the module's base directory, not the repo root), in JMH's built-in JSON format,
one entry per `depth` param (2, 4, 6, 8, 10), with `SampleTime` percentiles.

    sbt benchPgStop                    # stop the container when done

## Testing

    sbt benchmarksSql/test

Runs `JoinChainSuite` (query-generator unit tests) and `AdventureWorksMappingSuite`
(mapping/execution smoke tests) against `benchmark-postgres`, which is started
automatically as part of the test setup. This is scoped to the `benchmarksSql`
project deliberately — a plain, unscoped `sbt test` does not run these tests.

## Measurement caveats

Numbers from this benchmark are useful for comparing the *shape* of the depth→time
curve, but are not a clean measurement of Grackle's own cost in isolation:

- Each sample uses `Transactor.fromDriverManager` (see `BenchmarkDb.scala`), which
  opens and closes a fresh JDBC connection per invocation rather than pooling. TCP
  connect + auth is charged to every timed sample as a roughly constant offset, which
  does not invert the depth ordering but does mean absolute numbers overstate
  per-query cost, and proportionally affects the shallow depths (2, 4) more than the
  deep ones.
- Postgres's own buffer cache is external state that persists across JMH `@Param`
  values within a trial; JMH has no way to reset it between `depth` settings, so later
  params in a run may benefit from pages the earlier ones already warmed.

## Rebuilding after a Dockerfile change

`docker compose up` does not rebuild a `build:`-based service when its Dockerfile
changes, and the seeded AdventureWorks data lives in an anonymous volume that outlives
image rebuilds. So after bumping the pinned commit SHA in
`testdata/benchmark-pg/Dockerfile` (or otherwise changing that Dockerfile), `sbt
benchPgUp` alone will keep using the old image and old data. Force a clean rebuild
first:

    docker compose --profile benchmarks down -v benchmark-postgres
    docker compose --profile benchmarks build benchmark-postgres

then `sbt benchPgUp` as usual.
