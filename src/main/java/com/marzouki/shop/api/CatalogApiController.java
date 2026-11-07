package com.marzouki.shop.api;

import com.marzouki.shop.api.dto.CatalogDtos.CategoryResponse;
import com.marzouki.shop.api.dto.CatalogDtos.PageResponse;
import com.marzouki.shop.api.dto.CatalogDtos.ProductResponse;
import com.marzouki.shop.catalog.CategoryRepository;
import com.marzouki.shop.catalog.ProductRepository;
import com.marzouki.shop.catalog.ProductService;
import com.marzouki.shop.catalog.ShopQuery;
import com.marzouki.shop.catalog.dto.ProductForm;
import com.marzouki.shop.common.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
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

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Catalog", description = "Public reads; changes need the MANAGER role")
public class CatalogApiController {

    private static final int MAX_PAGE_SIZE = 50;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductService products;

    @GetMapping("/products")
    @Transactional(readOnly = true)
    @Operation(summary = "Search active products",
            description = "sort is one of name, price-asc, price-desc, newest. Page size is capped at 50.")
    public PageResponse<ProductResponse> search(@RequestParam(defaultValue = "") String q,
                                                @RequestParam(required = false) Long category,
                                                @RequestParam(defaultValue = "name") String sort,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "12") int size) {
        ShopQuery query = new ShopQuery();
        query.setQ(q);
        query.setCategory(category);
        query.setSort(sort);
        query.setPage(Math.max(page, 0));
        return PageResponse.of(products.shop(query, Math.min(Math.max(size, 1), MAX_PAGE_SIZE)), ProductResponse::of);
    }

    @GetMapping("/products/{id}")
    @Transactional(readOnly = true)
    @Operation(summary = "Get one active product")
    public ProductResponse product(@PathVariable Long id) {
        return productRepository.findByIdAndActiveTrue(id).map(ProductResponse::of)
                .orElseThrow(() -> new NotFoundException("Product", id));
    }

    @GetMapping("/categories")
    @Transactional(readOnly = true)
    @Operation(summary = "List categories with their subcategories")
    public List<CategoryResponse> categories() {
        return categoryRepository.findAllByOrderByTitleAsc().stream().map(CategoryResponse::of).toList();
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MANAGER')")
    @Transactional
    @Operation(summary = "Create a product (manager)")
    public ProductResponse create(@Valid @RequestBody ProductForm form) {
        return ProductResponse.of(products.get(products.create(form).getId()));
    }

    @PutMapping("/products/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    @Transactional
    @Operation(summary = "Update a product (manager)")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductForm form) {
        products.update(id, form);
        return ProductResponse.of(products.get(id));
    }

    @DeleteMapping("/products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MANAGER')")
    @Operation(summary = "Delete a product (manager)")
    public void delete(@PathVariable Long id) {
        products.delete(id);
    }
}