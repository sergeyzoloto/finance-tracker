-- UX-1, D-304: each user may choose how the app shows and takes day dates. NULL means as the browser shows them (the
-- default); otherwise one of four numeric patterns. The API keeps ISO dates whatever is chosen, and the import and
-- export files keep dd.MM.yyyy (D-274). Nothing reads or writes the column but the new code, so the image before this
-- migration (which ignores it) runs on this schema unchanged.
ALTER TABLE user_settings ADD COLUMN date_format TEXT;

ALTER TABLE user_settings ADD CONSTRAINT user_settings_date_format_check
    CHECK (date_format IN ('DD.MM.YYYY', 'DD/MM/YYYY', 'MM/DD/YYYY', 'YYYY-MM-DD'));
