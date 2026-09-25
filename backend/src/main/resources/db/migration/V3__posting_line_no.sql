-- A posting's position in its entry, so that an entry reads back with its postings in the order they were written:
-- the paying account first, then UNALLOCATED, and so on. Spring Data JDBC stores an entry's list of postings by this
-- index. The posting table had no rows anywhere when this was added, so the column needs no default.
ALTER TABLE posting ADD COLUMN line_no SMALLINT NOT NULL CHECK (line_no >= 0);
ALTER TABLE posting ADD UNIQUE (entry_id, line_no);

-- The unique constraint's index serves lookups by entry_id.
DROP INDEX posting_entry_id_idx;
