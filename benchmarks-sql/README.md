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
    sbt "benchmarksSql/Jmh/run -rf json -rff results.json"

`SqlJoinDepthBenchmark` carries explicit `@Fork(3)`, `@Warmup(iterations = 5)`, and
`@Measurement(iterations = 10)` annotations, so a bare run above already uses settings
sized for trustworthy results: 3 forks (a single fork would hide JIT profile pollution)
x (5 warmup + 10 measurement) iterations x 5 `depth` params. Those annotations set
iteration *counts* only; JMH's default iteration *time* of 10s still applies on top, so
the bare run above costs roughly 3 x 15 x 10s x 5 = 2,250s of measurement alone, plus 15
JVM fork launches — **approximately 40 minutes end to end**. Add `-prof gc` for
allocation-per-operation figures, which are near-deterministic and so remain meaningful
on a machine too noisy for trustworthy wall-clock numbers. For a quick sanity check
while iterating on the benchmark itself, override the annotations with explicit flags,
e.g. `sbt "benchmarksSql/Jmh/run -f 1 -wi 1 -i 1 -r 1s -w 1s SqlJoinDepthBenchmark"`.
See the doc comment on `SqlJoinDepthBenchmark` for more.

Results land in `benchmarks-sql/results.json` (JMH resolves the `-rff` path relative
to the module's base directory, not the repo root), in JMH's built-in JSON format,
one entry per `depth` param (2, 4, 6, 8, 10), with `SampleTime` percentiles.

    sbt benchPgStop                    # stop the container when done

## Testing

    sbt benchmarksSql/test

Runs `JoinChainSuite` (query-generator unit tests), `AdventureWorksMappingSuite`
(mapping/execution smoke tests), and `SqlQueryCountsSuite` (pins the query-count
invariants below — exactly 1 query per depth, plus a weaker N+1 bound as a safety net)
against `benchmark-postgres`, which is started automatically as part of the test setup.
This is scoped to the `benchmarksSql` project deliberately — a plain, unscoped `sbt
test` does not run these tests.

## Query counts (the headline metric)

    sbt "benchmarksSql/runMain grackle.benchmarks.sql.SqlQueryCounts"

Runs outside JMH entirely — it just wires `AdventureWorksMapping` up to
`DoobieMonitor.statsMonitor` and counts how many SQL statements Grackle issues per
`depth`, using the unpooled `BenchmarkDb.transactor` since nothing here is timed.
Prints a `depth`/`queries`/`rows` table and writes `benchmarks-sql/query-counts.json`
(gitignored, like `results.json`). Run it from the repo root as shown above: unlike
`Jmh / run`, plain `Compile / run` (what `runMain` uses) is not forked with the
module's base directory as its working directory, so the output path is spelled out
relative to the repo root rather than relying on sbt to resolve it.

Also unlike `Jmh / run`, `runMain` has no `benchPgUp` dependency wired in `build.sbt` —
run `sbt benchPgUp` first, or the harness will fail to connect.

The `rows` column reports the total rows Grackle's SQL fetched at each depth, and it is
not monotonic in `depth`: depth 7 fetches 5,677 rows while depths 8-10 fetch 5,558. This
reflects how the join's shape changes as deeper levels are added — it has nothing to do
with query count, which stays at 1 throughout (see below). The exact mechanism behind
the drop has not been established.

Query counts are fully deterministic: no JIT warmup, no GC, no scheduling noise, so a
single run needs no repetition and is exactly reproducible. That determinism is what
makes them the headline number for demonstrating N+1 immunity — the depth→time curve
below is useful for comparing *shape*, but query counts are the number that settles
the argument outright, independent of machine noise.

## Measurement caveats

Numbers from this benchmark are useful for comparing the *shape* of the depth→time
curve, but are not a clean measurement of Grackle's own cost in isolation:

- Connection setup is deliberately excluded from every timed sample: `@Setup(Level.Trial)`
  allocates a pooled `HikariTransactor` once per trial (max pool size 4, see
  `BenchmarkDb.transactorResource`) and `@TearDown` releases it, so TCP connect + auth —
  a significant source of non-Grackle variance — never runs inside the measured region.
  `BenchmarkDb.transactor` still exposes an unpooled `Transactor.fromDriverManager`; the
  query-count harness (below) and the test suite both use it, since neither times
  anything, but the benchmark itself always goes through the pooled transactor.
- Postgres's buffer cache remains external state that JMH itself has no way to reset
  between `depth` settings, but `@Setup(Level.Trial)` deliberately prewarms it
  (`BenchmarkDb.prewarm`, backed by `pg_prewarm`) across all 11 chain tables before every
  trial, so each `depth` value starts from a hot cache rather than an arbitrary one.
  `pg_prewarm(regclass)` only warms a table's heap — the chain tables' heaps total ~36MB
  of the ~42MB grand total, against the default 128MB `shared_buffers`; the remaining
  ~6MB of indexes are not prewarmed by this call and instead get warmed incidentally
  during warmup iterations.
- The query is rooted at a single country region (default `FR`) rather than spanning
  all of them, to keep the per-operation payload small. This benchmark uses
  `Mode.SampleTime`, where each iteration is time-bounded (10s by default), so wall-clock
  cost is `forks x iterations x iterationTime` regardless of per-operation cost — a
  smaller payload buys no extra forks or iterations. What it does buy is more *samples*:
  phase 1's depth-10 cost was ~354ms/op, so a 10s iteration collected only ~28 samples,
  far too few to support the p99 this README reports elsewhere. Cutting the payload
  roughly 10x yields hundreds of samples per iteration, which is what makes the
  percentile numbers meaningful. The depth→time curve should not be read as covering the
  dataset's full result-set size.

## Rebuilding after a seed-script or Dockerfile change

`testdata/benchmark-pg/install.sh` (which seeds the AdventureWorks data and, as of this
phase, also creates the `pg_prewarm` extension) only runs on a fresh, uninitialized
PGDATA. `docker compose up` neither rebuilds a `build:`-based service when its
Dockerfile changes nor re-runs initdb, and the seeded data lives in an anonymous volume
that outlives both container recreation and image rebuilds. So **anyone who ran an
earlier phase of this benchmark and then pulls a later one** — not just after editing
the Dockerfile — can end up with a stale volume whose seed script never ran the newer
steps. The concrete symptom of this phase's addition specifically is `@Setup` failing
with `function pg_prewarm(character varying) does not exist`. Fresh clones are
unaffected — this only bites existing checkouts with an already-seeded volume, so don't
do the rebuild dance unless you're actually upgrading one or hit that error.

Force a clean rebuild:

    docker compose --profile benchmarks down -v benchmark-postgres
    docker compose --profile benchmarks build benchmark-postgres

then `sbt benchPgUp` as usual.
