package com.example.financetracker.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.category.CategoryRepository;
import com.example.financetracker.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/transactions")
class TransactionController {

    /** More than two decimals is rejected rather than silently rounded. */
    record TransactionRequest(@NotNull Long categoryId, @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotNull LocalDate occurredOn, @Size(max = 500) String note) {
    }

    private final TransactionRepository transactions;
    private final CategoryRepository categories;

    TransactionController(TransactionRepository transactions, CategoryRepository categories) {
        this.transactions = transactions;
        this.categories = categories;
    }

    @GetMapping
    List<Transaction> list(@AuthenticationPrincipal CurrentUser user, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) Long categoryId) {
        return transactions.search(user.id(), from, to, categoryId);
    }

    @GetMapping("/{id}")
    Transaction get(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        return transactions.findByIdAndUserId(id, user.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Transaction create(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody TransactionRequest request) {
        return transactions.save(toTransaction(null, user, request));
    }

    @PutMapping("/{id}")
    Transaction update(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
            @Valid @RequestBody TransactionRequest request) {
        get(user, id);
        return transactions.save(toTransaction(id, user, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        if (!transactions.deleteByIdAndUserId(id, user.id())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private Transaction toTransaction(Long id, CurrentUser user, TransactionRequest request) {
        if (!categories.existsByIdAndUserId(request.categoryId(), user.id())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown category");
        }
        // Scale 2 as stored, so responses look the same whether just saved or read back.
        return new Transaction(id, user.id(), request.categoryId(), request.amount().setScale(2),
                request.occurredOn(), request.note());
    }
}
