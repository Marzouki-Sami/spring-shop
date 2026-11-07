package com.marzouki.shop.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Product> {

    @EntityGraph(attributePaths = {"subcategory", "subcategory.category"})
    @Override
    Page<Product> findAll(org.springframework.data.jpa.domain.Specification<Product> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"subcategory", "subcategory.category", "supplier", "productType"})
    List<Product> findAllByOrderByNameAsc();

    @EntityGraph(attributePaths = {"subcategory", "subcategory.category", "supplier", "productType"})
    Optional<Product> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"subcategory", "subcategory.category"})
    List<Product> findTop8ByActiveTrueOrderByCreatedAtDescIdDesc();

    @EntityGraph(attributePaths = {"subcategory", "subcategory.category"})
    Optional<Product> findByIdAndActiveTrue(Long id);

    @EntityGraph(attributePaths = {"subcategory", "subcategory.category"})
    List<Product> findByIdIn(java.util.Collection<Long> ids);

    long countByActiveTrue();

    /** Id and creation time of the active products, for the sitemap; no entities are loaded. */
    @Query("select p.id, p.createdAt from Product p where p.active = true order by p.id")
    List<Object[]> sitemapEntries(Pageable limit);

    java.util.Optional<Product> findBySkuIgnoreCase(String sku);

    @EntityGraph(attributePaths = {"subcategory", "subcategory.category"})
    List<Product> findBySubcategoryIdAndActiveTrueAndIdNotOrderByCreatedAtDescIdDesc(Long subcategoryId, Long id, Pageable pageable);

    boolean existsByProductTypeId(Long productTypeId);

    boolean existsBySkuIgnoreCase(String sku);

    boolean existsBySkuIgnoreCaseAndIdNot(String sku, Long id);

    boolean existsBySubcategoryId(Long subcategoryId);

    boolean existsBySupplierId(Long supplierId);
}