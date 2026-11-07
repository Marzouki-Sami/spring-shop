package com.marzouki.shop.order;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Coupon> {

    Optional<Coupon> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    /** Claims one use atomically; returns 0 when the limit was reached in the meantime. */
    @Modifying(flushAutomatically = true)
    @Query("update Coupon c set c.usedCount = c.usedCount + 1 where c.id = :id and (c.maxUses is null or c.usedCount < c.maxUses)")
    int claimUse(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query("update Coupon c set c.usedCount = c.usedCount - 1 where c.id = :id and c.usedCount > 0")
    int releaseUse(@Param("id") Long id);
}