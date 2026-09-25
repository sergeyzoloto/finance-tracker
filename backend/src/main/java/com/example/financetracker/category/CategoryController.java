package com.example.financetracker.category;

import java.util.List;

import com.example.financetracker.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/categories")
class CategoryController {

    record CategoryRequest(@NotBlank @Size(max = 100) String name, @NotNull Category.Type type) {
    }

    private final CategoryRepository categories;

    CategoryController(CategoryRepository categories) {
        this.categories = categories;
    }

    @GetMapping
    List<Category> list(@AuthenticationPrincipal CurrentUser user) {
        return categories.findAllByUserIdOrderByName(user.id());
    }

    @GetMapping("/{id}")
    Category get(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        return categories.findByIdAndUserId(id, user.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Category create(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody CategoryRequest request) {
        return categories.save(new Category(null, user.id(), request.name(), request.type()));
    }

    /**
     * Renames a category. The type is fixed once set: it decides the sign of every transaction already
     * filed under it, so changing it would silently rewrite history. A category created with the wrong
     * type can be deleted while it still has no transactions, then created again.
     */
    @PutMapping("/{id}")
    Category update(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
            @Valid @RequestBody CategoryRequest request) {
        Category existing = get(user, id);
        if (request.type() != existing.type()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A category's type cannot be changed");
        }
        return categories.save(new Category(id, user.id(), request.name(), existing.type()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        try {
            if (!categories.deleteByIdAndUserId(id, user.id())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Category still has transactions");
        }
    }
}
