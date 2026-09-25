package com.example.financetracker.ledger.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.example.financetracker.ledger.domain.CurrencyExchangeCommand;
import com.example.financetracker.ledger.domain.EntryCommand;
import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.domain.ExpenseCommand;
import com.example.financetracker.ledger.domain.ImportedCommand;
import com.example.financetracker.ledger.domain.IncomeCommand;
import com.example.financetracker.ledger.domain.LoanGivenCommand;
import com.example.financetracker.ledger.domain.LoanRepaidCommand;
import com.example.financetracker.ledger.domain.ManualCommand;
import com.example.financetracker.ledger.domain.OpeningBalanceCommand;
import com.example.financetracker.ledger.domain.SharedExpenseCommand;
import com.example.financetracker.ledger.domain.TransferCommand;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * An entry in a request body is one of the domain layer's command records, and its "kind" field says which, by the
 * names of {@link EntryKind}. The records know nothing of JSON; this mix-in adds the type information to
 * {@link EntryCommand}. {@link ImportedCommand} is left out: only the importer writes imported entries.
 * <p>
 * A command's constructor rejects fields its builder can't work with, and Jackson calls it, so such a body fails to
 * read. {@code ApiExceptionHandler} answers it as the ledger rule violation it is (422).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ExpenseCommand.class, name = "EXPENSE"),
        @JsonSubTypes.Type(value = IncomeCommand.class, name = "INCOME"),
        @JsonSubTypes.Type(value = TransferCommand.class, name = "TRANSFER"),
        @JsonSubTypes.Type(value = SharedExpenseCommand.class, name = "SHARED_EXPENSE"),
        @JsonSubTypes.Type(value = LoanGivenCommand.class, name = "LOAN_GIVEN"),
        @JsonSubTypes.Type(value = LoanRepaidCommand.class, name = "LOAN_REPAID"),
        @JsonSubTypes.Type(value = CurrencyExchangeCommand.class, name = "CURRENCY_EXCHANGE"),
        @JsonSubTypes.Type(value = OpeningBalanceCommand.class, name = "OPENING_BALANCE"),
        @JsonSubTypes.Type(value = ManualCommand.class, name = "MANUAL")})
interface EntryCommandJson {

    @Configuration(proxyBeanMethods = false)
    class Registration {

        @Bean
        Jackson2ObjectMapperBuilderCustomizer entryCommandJson() {
            return builder -> builder.mixIn(EntryCommand.class, EntryCommandJson.class);
        }

        /**
         * Adds "kind" to the OpenAPI description of {@link EntryCommand}, which springdoc leaves empty: it finds the
         * subtypes through the mix-in but not the property that tells them apart.
         */
        @Bean
        OpenApiCustomizer entryCommandKinds() {
            return openApi -> {
                Schema<?> command = openApi.getComponents().getSchemas().get(EntryCommand.class.getSimpleName());
                if (command == null) {
                    return;
                }
                Discriminator discriminator = new Discriminator().propertyName("kind");
                List<String> kinds = new ArrayList<>();
                for (JsonSubTypes.Type type : EntryCommandJson.class.getAnnotation(JsonSubTypes.class).value()) {
                    kinds.add(type.name());
                    discriminator.mapping(type.name(), "#/components/schemas/" + type.value().getSimpleName());
                }
                command.types(Set.of("object")).discriminator(discriminator).required(List.of("kind"))
                        .addProperty("kind", new StringSchema().types(Set.of("string"))._enum(kinds)
                                .description("Which command the body is, and so which of its fields apply"));
            };
        }
    }
}
