package com.marzouki.shop.order;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Order> {

    @EntityGraph(attributePaths = "user")
    @Override
    org.springframework.data.domain.Page<Order> findAll(org.springframework.data.jpa.domain.Specification<Order> spec, org.springframework.data.domain.Pageable pageable);

    /** Moves an order between statuses only if it is still in the expected one; 0 means someone else got there first. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query("update Order o set o.status = :to where o.id = :id and o.status = :from")
    int transition(@Param("id") Long id, @Param("from") OrderStatus from, @Param("to") OrderStatus to);

    @Query("select count(i) > 0 from OrderItem i where i.order.user.id = :userId and i.product.id = :productId "
            + "and i.order.status <> com.marzouki.shop.order.OrderStatus.CANCELLED")
    boolean hasPurchased(@Param("userId") Long userId, @Param("productId") Long productId);

    @Query("select coalesce(sum(o.total), 0) from Order o where o.status <> com.marzouki.shop.order.OrderStatus.CANCELLED and o.createdAt >= :since")
    java.math.BigDecimal revenueSince(@Param("since") java.time.Instant since);

    @Query("select count(o) from Order o where o.status <> com.marzouki.shop.order.OrderStatus.CANCELLED and o.createdAt >= :since")
    long countSince(@Param("since") java.time.Instant since);

    @Query("select o.status, count(o) from Order o group by o.status")
    List<Object[]> countByStatus();

    @Query("select max(i.sku), i.name, sum(i.quantity) from OrderItem i where i.order.status <> com.marzouki.shop.order.OrderStatus.CANCELLED "
            + "group by i.name order by sum(i.quantity) desc")
    List<Object[]> topSellers(org.springframework.data.domain.Pageable limit);

    @EntityGraph(attributePaths = {"items", "user"})
    Optional<Order> findWithItemsById(Long id);

    List<Order> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    /** The latest orders of one person, for a page that should stay light however much they have bought. */
    List<Order> findTop50ByUserIdOrderByCreatedAtDescIdDesc(Long userId);
}