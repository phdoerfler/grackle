-- A deliberately expensive computed column, used to make over-fetching measurable.
--
-- `heavy_fn` is IMMUTABLE, so PostgreSQL evaluates it ONLY for queries that actually project the
-- `heavy` column: it is pruned from the plan entirely otherwise (verified with EXPLAIN VERBOSE).
-- The column is surfaced through a VIEW rather than a Hibernate `@Formula`, so both the Grackle
-- and the ORM mapping see one ordinary column — an ORM-only formula would make the comparison
-- unfair. The result: Grackle, which selects only the columns a query requests, never pays for
-- `heavy` unless it is asked for; the ORM's whole-entity fetch selects it every time, paying
-- `heavy_fn` once per row. That cost is pure CPU (no disk, no network), so it stays visible even
-- though the whole database fits in RAM.
--
-- This is a synthetic stand-in for real expensive-to-produce columns — decrypted fields, computed
-- aggregates, geometry transforms, formatted or extracted values — not a claim about AdventureWorks
-- itself. Tune the `generate_series` bound to make the effect larger or smaller.

CREATE FUNCTION heavy_fn(seed integer) RETURNS integer
  LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT count(*)::integer
  FROM generate_series(1, 2000) AS g
  WHERE (g % 7) = (seed % 7);
$$;

-- `wide` is the other flavour of over-fetch: cheap to compute but ~2KB of bytes per row, so its
-- cost shows up on the WIRE (bandwidth) rather than on the CPU. `heavy` isolates compute cost,
-- `wide` isolates transfer cost; the ORM over-fetches both on every Person load, Grackle neither
-- unless asked. (~2016 chars = 63 * md5, and it varies per row so it does not compress to nothing.)
CREATE VIEW person.person_heavy AS
  SELECT
    p.*,
    heavy_fn(p.businessentityid) AS heavy,
    repeat(md5(p.businessentityid::text), 63) AS wide
  FROM person.person AS p;
