package com.marzouki.shop.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    /** Variants a customer may buy right now, with what the cart and checkout pages show about them. */
    @Query("select distinct v from ProductVariant v join fetch v.product p join fetch p.subcategory s join fetch s.category "
            + "left join fetch v.optionValues ov left join fetch ov.optionType "
            + "where v.id in :ids and v.active = true and p.active = true")
    List<ProductVariant> findPurchasable(@Param("ids") Collection<Long> ids);

    @Query("select distinct v from ProductVariant v join fetch v.product p join fetch p.subcategory s join fetch s.category "
            + "left join fetch v.optionValues ov left join fetch ov.optionType "
            + "where v.id = :id and v.active = true and p.active = true")
    Optional<ProductVariant> findPurchasableById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"product", "optionValues", "optionValues.optionType"})
    Optional<ProductVariant> findWithDetailsById(Long id);

    Optional<ProductVariant> findBySku(String sku);

    String MATCHING = "from ProductVariant v where v.product.id = :productId and v.active in :actives "
            + "and v.stock between :minStock and :maxStock "
            + "and (:q = '' or lower(v.sku) like :q escape '\\' "
            + "or exists (select 1 from v.optionValues ov where lower(ov.label) like :q escape '\\'))";

    @Query("select v " + MATCHING + " order by v.position, v.id")
    org.springframework.data.domain.Page<ProductVariant> search(@Param("productId") Long productId,
            @Param("actives") Collection<Boolean> actives, @Param("minStock") int minStock,
            @Param("maxStock") int maxStock, @Param("q") String q, Pageable pageable);

    @Query("select v.id " + MATCHING + " order by v.position, v.id")
    List<Long> searchIds(@Param("productId") Long productId, @Param("actives") Collection<Boolean> actives,
            @Param("minStock") int minStock, @Param("maxStock") int maxStock, @Param("q") String q, Pageable limit);

    @Query("select v from ProductVariant v where v.product.id = :productId and v.id in :ids order by v.position, v.id")
    List<ProductVariant> findByProductAndIds(@Param("productId") Long productId, @Param("ids") Collection<Long> ids);

    long countByProductId(Long productId);

    @Query("select coalesce(sum(v.stock), 0) from ProductVariant v where v.product.id = :productId and v.active = true")
    long sellableStock(@Param("productId") Long productId);

    /** Option type id and value id of every value some variant of the product uses. */
    @Query("select distinct ov.optionType.id, ov.id from ProductVariant v join v.optionValues ov where v.product.id = :productId")
    List<Object[]> usedValueIds(@Param("productId") Long productId);

    boolean existsBySkuIgnoreCase(String sku);

    boolean existsBySkuIgnoreCaseAndIdNot(String sku, Long id);

    @Query("select v.id from ProductVariant v join v.product p where v.active = true and p.active = true "
            + "and v.stock <= coalesce(p.lowStockThreshold, :level) order by v.stock asc, p.name asc, v.id asc")
    List<Long> lowStockIds(@Param("level") int level, Pageable limit);

    @EntityGraph(attributePaths = {"product", "optionValues", "optionValues.optionType"})
    List<ProductVariant> findWithDetailsByIdIn(Collection<Long> ids);

    /** The lowest-stock items first. The page is cut on ids so the database, not memory, applies the limit. */
    default List<ProductVariant> lowStock(int level, Pageable limit) {
        List<Long> ids = lowStockIds(level, limit);
        if (ids.isEmpty()) {
            return List.of();
        }
        java.util.Map<Long, ProductVariant> byId = new java.util.HashMap<>();
        findWithDetailsByIdIn(ids).forEach(v -> byId.put(v.getId(), v));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    /** Takes stock only if enough is left, in one atomic statement so two buyers can never oversell. */
    @Modifying(flushAutomatically = true)
    @Query("update ProductVariant v set v.stock = v.stock - :quantity where v.id = :id and v.active = true "
            + "and v.stock >= :quantity and exists (select 1 from Product p where p.id = v.product.id and p.active = true)")
    int takeStock(@Param("id") Long id, @Param("quantity") int quantity);

    @Modifying(flushAutomatically = true)
    @Query("update ProductVariant v set v.stock = v.stock + :quantity where v.id = :id")
    int returnStock(@Param("id") Long id, @Param("quantity") int quantity);

    /** Changes stock by a signed amount, refusing to go below zero. */
    @Modifying(flushAutomatically = true)
    @Query("update ProductVariant v set v.stock = v.stock + :delta where v.id = :id and v.stock + :delta >= 0")
    int adjust(@Param("id") Long id, @Param("delta") int delta);
}