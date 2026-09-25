package com.example.financetracker.ledger.api;

import static com.example.financetracker.ledger.api.AccountController.CODE;
import static com.example.financetracker.ledger.api.AccountController.CODE_MESSAGE;
import static com.example.financetracker.ledger.api.AccountController.NOT_BLANK;

import java.util.List;

import com.example.financetracker.ledger.CategoryService;
import com.example.financetracker.ledger.CategoryView;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/categories")
class CategoryController {

    /** @param code the category's identifier, unique per user and fixed once created, such as GROCERIES */
    record NewCategory(@NotNull @Size(max = 50) @Pattern(regexp = CODE, message = CODE_MESSAGE) String code,
            @NotBlank @Size(max = 100) String name, @NotNull CategoryType type) {
    }

    /**
     * Rename, archive or restore a category; fields left out stay as they are.
     *
     * @param archived true archives the category, false restores it
     * @param type may be sent back as it is; a different type is refused with 409, as a category's type never changes
     */
    record CategoryPatch(@Size(max = 100) @Pattern(regexp = NOT_BLANK, message = "must not be blank") String name,
            Boolean archived, CategoryType type) {
    }

    private final CategoryService categories;

    CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    /** All the user's categories, archived ones included, by name. */
    @GetMapping
    List<CategoryView> list(CurrentUser user) {
        return categories.list(user.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CategoryView create(CurrentUser user, @Valid @RequestBody NewCategory category) {
        return categories.create(user.id(), category.code(), category.name().strip(), category.type());
    }

    @PatchMapping("/{id}")
    CategoryView update(CurrentUser user, @PathVariable long id, @Valid @RequestBody CategoryPatch patch) {
        return categories.update(user.id(), id, patch.name() == null ? null : patch.name().strip(), patch.archived(),
                patch.type());
    }
}
