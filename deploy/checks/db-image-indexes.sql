-- OPS-6 (D-939): the indexes that the release notes of a Postgres minor release advise to REINDEX, read only, one line
-- each, none expected: a btree index on a bpchar column or expression without a length (CHAR(n) is not affected), every
-- GIN index, every GiST index on pg_trgm's gist_trgm_ops. System catalogs and TOAST tables are left out. Output: index,
-- method, reason. `deploy.sh db-image` refuses while it prints a line; the checklist's pre-flight runs it as well.
SELECT format('%s|%s|%s', c.oid::regclass, am.amname, why.text)
FROM pg_index i
JOIN pg_class c ON c.oid = i.indexrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_am am ON am.oid = c.relam
CROSS JOIN LATERAL (
  SELECT 'btree on bpchar without a length'::text AS text
  WHERE am.amname = 'btree'
    AND EXISTS (SELECT 1 FROM pg_attribute a
                WHERE a.attrelid = c.oid AND a.attnum > 0 AND a.atttypid = 'bpchar'::regtype AND a.atttypmod = -1)
  UNION ALL
  SELECT 'GIN'::text WHERE am.amname = 'gin'
  UNION ALL
  SELECT 'GiST on gist_trgm_ops'::text
  WHERE am.amname = 'gist'
    AND EXISTS (SELECT 1 FROM unnest(i.indclass::oid[]) AS k(oid) JOIN pg_opclass o ON o.oid = k.oid
                WHERE o.opcname = 'gist_trgm_ops')
) AS why
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
ORDER BY 1;
