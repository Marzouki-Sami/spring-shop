package com.marzouki.shop.catalog;

import com.marzouki.shop.common.ListQuery;
import com.marzouki.shop.common.Specs;
import com.marzouki.shop.engagement.Review;
import com.marzouki.shop.order.OrderService;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** The filters behind the shop page and the back-office product list. */
final class ProductSpecs {

    private ProductSpecs() {
    }

    private static Subquery<Long> variantWhere(jakarta.persistence.criteria.CriteriaQuery<?> query, CriteriaBuilder cb,
                                               Root<Product> product, VariantPredicate extra) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<ProductVariant> v = sub.from(ProductVariant.class);
        sub.select(v.get("id")).where(cb.equal(v.get("product"), product), cb.isTrue(v.get("active")), extra.apply(cb, v, product));
        return sub;
    }

    @FunctionalInterface
    private interface VariantPredicate {
        Predicate apply(CriteriaBuilder cb, Root<ProductVariant> variant, Root<Product> product);
    }

    private static Expression<Double> averageRating(jakarta.persistence.criteria.CriteriaQuery<?> query, CriteriaBuilder cb, Root<Product> product) {
        Subquery<Double> sub = query.subquery(Double.class);
        Root<Review> r = sub.from(Review.class);
        sub.select(cb.avg(r.<Integer>get("rating"))).where(cb.equal(r.get("product"), product));
        return sub;
    }

    static Specification<Product> storefront(ShopQuery q) {
        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            all.add(cb.isTrue(root.get("active")));
            Predicate text = Specs.<Product>text(q.likePattern(), "name", "description").toPredicate(root, query, cb);
            all.add(text);
            if (q.getSubcategory() != null) {
                all.add(cb.equal(root.get("subcategory").get("id"), q.getSubcategory()));
            } else if (q.getCategory() != null) {
                all.add(cb.equal(root.get("subcategory").get("category").get("id"), q.getCategory()));
            }
            if (q.minPriceValue() != null || q.maxPriceValue() != null) {
                // A variant can carry its own price, so the range is checked against what each variant really costs.
                all.add(cb.exists(variantWhere(query, cb, root, (b, v, p) -> {
                    Expression<java.math.BigDecimal> cost = b.coalesce(v.<java.math.BigDecimal>get("price"), p.<java.math.BigDecimal>get("price"));
                    List<Predicate> range = new ArrayList<>();
                    if (q.minPriceValue() != null) {
                        range.add(b.greaterThanOrEqualTo(cost, q.minPriceValue()));
                    }
                    if (q.maxPriceValue() != null) {
                        range.add(b.lessThanOrEqualTo(cost, q.maxPriceValue()));
                    }
                    return b.and(range.toArray(Predicate[]::new));
                })));
            }
            if (q.isInStock()) {
                all.add(cb.exists(variantWhere(query, cb, root, (b, v, p) -> b.greaterThan(v.<Integer>get("stock"), 0))));
            }
            if (q.getMinRating() > 0) {
                all.add(cb.greaterThanOrEqualTo(averageRating(query, cb, root), (double) q.getMinRating()));
            }
            boolean counting = query.getResultType() == Long.class || query.getResultType() == long.class;
            if (!counting && q.getSort().equals("rating")) {
                query.orderBy(cb.desc(cb.coalesce(averageRating(query, cb, root), 0.0)), cb.asc(root.get("name")), cb.asc(root.get("id")));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
    }

    static Specification<Product> backOffice(ListQuery q) {
        Specification<Product> filter = switch (q.getStatus()) {
            case "ACTIVE" -> (root, query, cb) -> cb.isTrue(root.get("active"));
            case "HIDDEN" -> (root, query, cb) -> cb.isFalse(root.get("active"));
            case "LOW" -> (root, query, cb) -> cb.exists(variantWhere(query, cb, root,
                    (b, v, p) -> b.lessThanOrEqualTo(v.<Integer>get("stock"), b.coalesce(p.<Integer>get("lowStockThreshold"), Product.DEFAULT_LOW_STOCK))));
            case "OUT" -> (root, query, cb) -> cb.not(cb.exists(variantWhere(query, cb, root,
                    (b, v, p) -> b.greaterThan(v.<Integer>get("stock"), 0))));
            default -> Specs.none();
        };
        return Specs.<Product>text(q.like(), "name", "sku").and(filter);
    }
}