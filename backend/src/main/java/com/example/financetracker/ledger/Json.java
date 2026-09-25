package com.example.financetracker.ledger;

import java.sql.SQLException;

import org.postgresql.util.PGobject;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/** The text of a JSONB column. Its converters are registered in {@code JdbcConfiguration}. */
public record Json(String value) {

    @WritingConverter
    public enum ToPgObject implements Converter<Json, PGobject> {
        INSTANCE;

        @Override
        public PGobject convert(Json json) {
            PGobject object = new PGobject();
            object.setType("jsonb");
            try {
                object.setValue(json.value());
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return object;
        }
    }

    @ReadingConverter
    public enum FromPgObject implements Converter<PGobject, Json> {
        INSTANCE;

        @Override
        public Json convert(PGobject object) {
            return new Json(object.getValue());
        }
    }
}
