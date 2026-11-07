package com.marzouki.shop.catalog;

import com.marzouki.shop.catalog.dto.BulkVariantForm;
import com.marzouki.shop.catalog.dto.OptionValueForm;
import com.marzouki.shop.catalog.dto.VariantForm;
import com.marzouki.shop.common.ConflictException;
import com.marzouki.shop.common.NotFoundException;
import com.marzouki.shop.order.OrderItemRepository;
import com.marzouki.shop.order.OrderService;
import com.marzouki.shop.order.StockMovement;
import com.marzouki.shop.order.StockService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds and maintains the variants of a product that has a product type. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VariantService {

    static final int MAX_COMBINATIONS = 200;
    static final int MAX_BULK = 1000;

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final ProductTypeRepository types;
    private final OrderItemRepository orderItems;
    private final StockService stock;
    private final OptionService options;

    /** The product with its type, without loading every variant. */
    public Product product(Long productId) {
        Product product = products.findById(productId).orElseThrow(() -> new NotFoundException("Product", productId));
        if (product.getProductType() != null) {
            product.getProductType().getName();
        }
        return product;
    }

    /** The product with every variant and its options loaded; for jobs that work on all of them. */
    public Product productWithVariants(Long productId) {
        Product product = products.findWithDetailsById(productId)
                .orElseThrow(() -> new NotFoundException("Product", productId));
        product.getVariants().forEach(v -> v.getOptionValues().forEach(o -> o.getOptionType().getName()));
        return product;
    }

    /** One page of a product's variants plus the totals shown above the table. */
    public record Overview(Page<ProductVariant> page, long totalVariants, long sellableStock) {
    }

    public Overview overview(Long productId, VariantQuery query) {
        Page<ProductVariant> page = search(productId, query, query.getPage());
        if (page.getTotalPages() > 0 && query.getPage() >= page.getTotalPages()) {
            page = search(productId, query, page.getTotalPages() - 1);
        }
        page.forEach(v -> v.getOptionValues().forEach(o -> o.getOptionType().getName()));
        return new Overview(page, variants.countByProductId(productId), variants.sellableStock(productId));
    }

    private Page<ProductVariant> search(Long productId, VariantQuery query, int pageNumber) {
        Filter f = Filter.of(query.getStatus(), lowLevel(productId));
        return variants.search(productId, f.actives(), f.minStock(), f.maxStock(), query.likePattern(),
                PageRequest.of(pageNumber, query.getSize()));
    }

    private int lowLevel(Long productId) {
        return products.findById(productId).map(Product::getLowStockLevel).orElse(Product.DEFAULT_LOW_STOCK);
    }

    private record Filter(List<Boolean> actives, int minStock, int maxStock) {
        static Filter of(VariantQuery.Status status, int lowLevel) {
            return switch (status) {
                case ACTIVE -> new Filter(List.of(true), 0, Integer.MAX_VALUE);
                case INACTIVE -> new Filter(List.of(false), 0, Integer.MAX_VALUE);
                // Sold-out items have their own filter, so LOW means running low but not yet gone.
                case LOW -> new Filter(List.of(true), 1, lowLevel);
                case OUT -> new Filter(List.of(true, false), 0, 0);
                default -> new Filter(List.of(true, false), 0, Integer.MAX_VALUE);
            };
        }
    }

    /** The option types (with all their values) the product's type allows. */
    public java.util.Collection<OptionType> allowedOptions(Product product) {
        if (product.getProductType() == null) {
            return List.of();
        }
        return types.findWithOptionsById(product.getProductType().getId()).orElseThrow().getOptionTypes();
    }

    public ProductVariant getForStock(Long variantId) {
        return variants.findWithDetailsById(variantId).orElseThrow(() -> new NotFoundException("Variant", variantId));
    }

    public ProductVariant get(Long productId, Long variantId) {
        ProductVariant variant = variants.findWithDetailsById(variantId)
                .orElseThrow(() -> new NotFoundException("Variant", variantId));
        if (!variant.getProduct().getId().equals(productId)) {
            throw new NotFoundException("Variant", variantId);
        }
        return variant;
    }

    /**
     * Creates every combination of the chosen option values that does not exist yet.
     *
     * @param chosen option type id mapped to the ids of the values to offer
     * @return how many variants were created
     */
    /** How many variants a generate request created and how many of the requested combinations already existed. */
    public record Generated(int created, int alreadyExisted) {
    }

    @Transactional
    public Generated generate(Long productId, Map<Long, Set<Long>> chosen) {
        Product product = product(productId);
        if (!product.isOptioned()) {
            throw new ConflictException("This product has no type, so it has no options to combine");
        }
        List<List<OptionValue>> lists = new ArrayList<>();
        for (OptionType type : allowedOptions(product)) {
            Set<Long> ids = chosen.getOrDefault(type.getId(), Set.of());
            List<OptionValue> picked = type.getValues().stream().filter(v -> ids.contains(v.getId())).toList();
            if (picked.isEmpty()) {
                throw new ConflictException("Choose at least one " + type.getName().toLowerCase());
            }
            lists.add(picked);
        }
        long total = lists.stream().mapToLong(List::size).reduce(1, (a, b) -> a * b);
        if (total > MAX_COMBINATIONS) {
            throw new ConflictException("That would create " + total + " variants; the limit is " + MAX_COMBINATIONS);
        }

        Set<String> existing = product.getVariants().stream().map(ProductVariant::getOptionKey).collect(Collectors.toSet());
        int created = 0;
        int skipped = 0;
        for (List<OptionValue> combo : combinations(lists)) {
            String key = combo.stream().map(OptionValue::getId).sorted().map(String::valueOf).collect(Collectors.joining("-"));
            if (!existing.add(key)) {
                skipped++;
                continue;
            }
            ProductVariant variant = product.addVariant(freeSku(product.getSku(), combo), 0);
            variant.getOptionValues().addAll(combo);
            created++;
        }
        return new Generated(created, skipped);
    }

    /** Adds a value to one of the option types the product's own type allows. */
    @Transactional
    public OptionValue addValue(Long productId, Long optionTypeId, OptionValueForm form) {
        Product product = product(productId);
        if (allowedOptions(product).stream().noneMatch(t -> t.getId().equals(optionTypeId))) {
            throw new ConflictException("This product's type does not offer that option");
        }
        return options.addValue(optionTypeId, form);
    }

    @Transactional
    public ProductVariant update(Long productId, Long variantId, VariantForm form) {
        ProductVariant variant = get(productId, variantId);
        if (variants.existsBySkuIgnoreCaseAndIdNot(form.getSku(), variantId)
                || products.existsBySkuIgnoreCaseAndIdNot(form.getSku(), productId)) {
            throw new ConflictException("SKU '" + form.getSku() + "' is already used");
        }
        variant.setSku(form.getSku());
        variant.setPrice(form.getPrice());
        variant.setActive(form.isActive());
        return variant;
    }

    /** Variants that were ever ordered must stay (deactivate them instead); the rest can go. */
    @Transactional
    public void delete(Long productId, Long variantId) {
        ProductVariant variant = get(productId, variantId);
        Product product = variant.getProduct();
        if (!product.isOptioned()) {
            throw new ConflictException("A product without options always keeps its single variant");
        }
        if (orderItems.existsByVariantId(variantId)) {
            throw new ConflictException("This variant has been ordered, so it can only be deactivated");
        }
        product.getVariants().remove(variant);
    }

    private String freeSku(String base, List<OptionValue> combo) {
        String suffix = combo.stream().map(v -> Skus.fromText(v.getLabel(), 20).replace("-", ""))
                .filter(s -> !s.isEmpty()).collect(Collectors.joining("-"));
        String candidate = trim(base + (suffix.isEmpty() ? "" : "-" + suffix), 60);
        int counter = 2;
        while (variants.existsBySkuIgnoreCase(candidate)) {
            String tail = "-" + counter++;
            candidate = trim(base + (suffix.isEmpty() ? "" : "-" + suffix), 60 - tail.length()) + tail;
        }
        return candidate;
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static List<List<OptionValue>> combinations(List<List<OptionValue>> lists) {
        List<List<OptionValue>> result = new ArrayList<>();
        result.add(new ArrayList<>());
        for (List<OptionValue> options : lists) {
            List<List<OptionValue>> next = new ArrayList<>();
            for (List<OptionValue> prefix : result) {
                for (OptionValue option : options) {
                    List<OptionValue> combo = new ArrayList<>(prefix);
                    combo.add(option);
                    next.add(combo);
                }
            }
            result = next;
        }
        return result;
    }

    /** Used by the page to preselect the values that already have variants. */
    public Map<Long, Set<Long>> usedValues(Long productId) {
        Map<Long, Set<Long>> used = new java.util.HashMap<>();
        for (Object[] row : variants.usedValueIds(productId)) {
            used.computeIfAbsent((Long) row[0], k -> new LinkedHashSet<>()).add((Long) row[1]);
        }
        return used;
    }

    /** What a bulk action did: how many variants changed and how many were left alone, with the reason. */
    public record BulkResult(int changed, int skipped, String skippedReason) {
    }

    @Transactional
    public BulkResult bulk(Long productId, VariantQuery query, BulkVariantForm form) {
        Product product = product(productId);
        if (form.getAction() == null) {
            throw new ConflictException("Choose what to do");
        }
        List<ProductVariant> targets = targets(productId, query, form);
        if (targets.isEmpty()) {
            throw new ConflictException("No variants selected");
        }
        return switch (form.getAction()) {
            case ACTIVATE -> setActive(targets, true);
            case DEACTIVATE -> setActive(targets, false);
            case SET_PRICE -> {
                if (form.getPrice() == null) {
                    throw new ConflictException("Enter the price to apply");
                }
                targets.forEach(v -> v.setPrice(form.getPrice()));
                yield new BulkResult(targets.size(), 0, null);
            }
            case CLEAR_PRICE -> {
                targets.forEach(v -> v.setPrice(null));
                yield new BulkResult(targets.size(), 0, null);
            }
            case ADJUST_STOCK -> adjustStock(targets, form);
            case DELETE -> delete(product, targets);
        };
    }

    private List<ProductVariant> targets(Long productId, VariantQuery query, BulkVariantForm form) {
        if (form.getScope() == BulkVariantForm.Scope.FILTERED) {
            Filter f = Filter.of(query.getStatus(), lowLevel(productId));
            List<Long> ids = variants.searchIds(productId, f.actives(), f.minStock(), f.maxStock(), query.likePattern(),
                    PageRequest.of(0, MAX_BULK + 1));
            if (ids.size() > MAX_BULK) {
                throw new ConflictException("More than " + MAX_BULK + " variants match. Narrow the filter or select rows instead.");
            }
            return ids.isEmpty() ? List.of() : variants.findByProductAndIds(productId, ids);
        }
        Set<Long> ids = new LinkedHashSet<>(form.getIds());
        return ids.isEmpty() ? List.of() : variants.findByProductAndIds(productId, ids);
    }

    private BulkResult setActive(List<ProductVariant> targets, boolean active) {
        int changed = 0;
        for (ProductVariant v : targets) {
            if (v.isActive() != active) {
                v.setActive(active);
                changed++;
            }
        }
        return new BulkResult(changed, targets.size() - changed, "already " + (active ? "active" : "inactive"));
    }

    private BulkResult adjustStock(List<ProductVariant> targets, BulkVariantForm form) {
        int delta = form.getDelta() == null ? 0 : form.getDelta();
        if (delta == 0) {
            throw new ConflictException("Enter a stock change other than zero");
        }
        if (form.getReason() == null || !StockMovement.Reason.manual().contains(form.getReason())) {
            throw new ConflictException("Choose a reason");
        }
        int changed = 0;
        for (ProductVariant v : targets) {
            if (stock.tryAdjust(v, delta, form.getReason(), form.getNote())) {
                changed++;
            }
        }
        return new BulkResult(changed, targets.size() - changed, "not enough stock to remove that many");
    }

    private BulkResult delete(Product product, List<ProductVariant> targets) {
        if (!product.isOptioned()) {
            throw new ConflictException("A product without options always keeps its single variant");
        }
        Set<Long> ordered = orderItems.findOrderedVariantIds(targets.stream().map(ProductVariant::getId).toList());
        Set<Long> doomed = targets.stream().map(ProductVariant::getId).filter(id -> !ordered.contains(id))
                .collect(Collectors.toSet());
        product.getVariants().removeIf(v -> doomed.contains(v.getId()));
        return new BulkResult(doomed.size(), targets.size() - doomed.size(), "already ordered, deactivate them instead");
    }
}