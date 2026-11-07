package com.marzouki.shop.catalog;

import com.marzouki.shop.catalog.dto.ProductForm;
import com.marzouki.shop.common.ConflictException;
import com.marzouki.shop.common.NotFoundException;
import com.marzouki.shop.order.OrderItemRepository;
import com.marzouki.shop.order.StockMovement;
import com.marzouki.shop.order.StockService;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final SubcategoryRepository subcategories;
    private final SupplierRepository suppliers;
    private final ProductTypeRepository types;
    private final OrderItemRepository orderItems;
    private final StockService stock;
    private final com.marzouki.shop.media.ProductImageService imageFiles;
    private final CatalogMapper mapper;

    public List<Product> findAll() {
        List<Product> all = products.findAllByOrderByNameAsc();
        ProductViews.warm(all);
        return all;
    }

    /** One page of the shop: only active products, filtered and sorted as the customer chose. */
    public org.springframework.data.domain.Page<Product> shop(ShopQuery query) {
        return shop(query, ShopQuery.PAGE_SIZE);
    }

    public org.springframework.data.domain.Page<Product> shop(ShopQuery query, int pageSize) {
        org.springframework.data.domain.Sort sort = switch (query.getSort()) {
            case "price-asc" -> org.springframework.data.domain.Sort.by("price").ascending().and(org.springframework.data.domain.Sort.by("id"));
            case "price-desc" -> org.springframework.data.domain.Sort.by("price").descending().and(org.springframework.data.domain.Sort.by("id"));
            case "newest" -> org.springframework.data.domain.Sort.by("createdAt").descending().and(org.springframework.data.domain.Sort.by("id").descending());
            case "rating" -> org.springframework.data.domain.Sort.unsorted();
            default -> org.springframework.data.domain.Sort.by("name").ascending().and(org.springframework.data.domain.Sort.by("id"));
        };
        var page = products.findAll(ProductSpecs.storefront(query),
                org.springframework.data.domain.PageRequest.of(query.getPage(), pageSize, sort));
        ProductViews.warm(page.getContent());
        return page;
    }

    /** One page of the back-office product list. */
    public org.springframework.data.domain.Page<Product> search(com.marzouki.shop.common.ListQuery query) {
        org.springframework.data.domain.Sort sort = switch (query.getSort()) {
            case "newest" -> org.springframework.data.domain.Sort.by("createdAt").descending().and(org.springframework.data.domain.Sort.by("id").descending());
            case "sku" -> org.springframework.data.domain.Sort.by("sku").ascending();
            case "price-asc" -> org.springframework.data.domain.Sort.by("price").ascending().and(org.springframework.data.domain.Sort.by("id"));
            case "price-desc" -> org.springframework.data.domain.Sort.by("price").descending().and(org.springframework.data.domain.Sort.by("id"));
            default -> org.springframework.data.domain.Sort.by("name").ascending().and(org.springframework.data.domain.Sort.by("id"));
        };
        var page = products.findAll(ProductSpecs.backOffice(query), query.pageRequest(sort));
        ProductViews.warm(page.getContent());
        page.forEach(p -> {
            if (p.getProductType() != null) {
                p.getProductType().getName();
            }
        });
        return page;
    }

    public Product get(Long id) {
        Product product = products.findWithDetailsById(id).orElseThrow(() -> new NotFoundException("Product", id));
        ProductViews.warm(List.of(product));
        return product;
    }

    public ProductForm formFor(Long id) {
        return mapper.toForm(get(id));
    }

    @Transactional
    public Product create(ProductForm form) {
        if (form.getSku() == null || form.getSku().isBlank()) {
            form.setSku(uniqueSkuFor(form.getName()));
        }
        requireFreeSku(form.getSku(), null);
        Product product = new Product();
        mapper.apply(form, product);
        attachRelations(product, form);
        ProductVariant initial = product.getProductType() == null ? product.addVariant(product.getSku(), 0) : null;
        products.saveAndFlush(product);
        if (initial != null && form.getStock() > 0) {
            stock.adjust(initial.getId(), form.getStock(), StockMovement.Reason.INITIAL, null);
        }
        return product;
    }

    @Transactional
    public Product update(Long id, ProductForm form) {
        Product product = get(id);
        if (form.getSku() == null || form.getSku().isBlank()) {
            form.setSku(product.getSku());
        }
        if (products.existsBySkuIgnoreCaseAndIdNot(form.getSku(), id)) {
            throw new ConflictException("SKU '" + form.getSku() + "' is already used");
        }
        Long oldType = product.getProductType() == null ? null : product.getProductType().getId();
        boolean typeChanged = !Objects.equals(oldType, form.getProductTypeId());
        boolean skuChanged = !product.getSku().equalsIgnoreCase(form.getSku());
        if (skuChanged && !typeChanged) {
            Long own = product.isOptioned() || product.getDefaultVariant() == null ? null : product.getDefaultVariant().getId();
            requireFreeVariantSku(form.getSku(), own);
        }
        mapper.apply(form, product);
        attachRelations(product, form);
        if (typeChanged) {
            if (orderItems.existsByProductId(id)) {
                throw new ConflictException("This product has orders, so its type can no longer be changed");
            }
            product.getVariants().clear();
            products.flush();
            requireFreeVariantSku(product.getSku(), null);
            if (!product.isOptioned()) {
                product.addVariant(product.getSku(), 0);
            }
        } else if (skuChanged && !product.isOptioned() && product.getDefaultVariant() != null) {
            product.getDefaultVariant().setSku(product.getSku());
        }
        return product;
    }

    @Transactional
    public void delete(Long id) {
        Product product = get(id);
        imageFiles.deleteFilesAfterCommit(product.getImages());
        products.delete(product);
    }

    private String uniqueSkuFor(String name) {
        String base = Skus.fromText(name, 36);
        if (base.isEmpty()) {
            base = "PRODUCT";
        }
        String candidate = base;
        int counter = 2;
        while (products.existsBySkuIgnoreCase(candidate) || variants.existsBySkuIgnoreCase(candidate)) {
            candidate = base + "-" + counter++;
        }
        return candidate;
    }

    private void requireFreeVariantSku(String sku, Long ignoreVariantId) {
        boolean taken = ignoreVariantId == null ? variants.existsBySkuIgnoreCase(sku) : variants.existsBySkuIgnoreCaseAndIdNot(sku, ignoreVariantId);
        if (taken) {
            throw new ConflictException("SKU '" + sku + "' is already used");
        }
    }

    private void requireFreeSku(String sku, Long ignoreVariantId) {
        boolean taken = ignoreVariantId == null
                ? products.existsBySkuIgnoreCase(sku) || variants.existsBySkuIgnoreCase(sku)
                : variants.existsBySkuIgnoreCaseAndIdNot(sku, ignoreVariantId);
        if (taken) {
            throw new ConflictException("SKU '" + sku + "' is already used");
        }
    }

    private void attachRelations(Product product, ProductForm form) {
        product.setSubcategory(subcategories.findById(form.getSubcategoryId())
                .orElseThrow(() -> new NotFoundException("Subcategory", form.getSubcategoryId())));
        product.setSupplier(form.getSupplierId() == null ? null
                : suppliers.findById(form.getSupplierId())
                        .orElseThrow(() -> new NotFoundException("Supplier", form.getSupplierId())));
        product.setProductType(form.getProductTypeId() == null ? null
                : types.findById(form.getProductTypeId())
                        .orElseThrow(() -> new NotFoundException("Product type", form.getProductTypeId())));
    }
}