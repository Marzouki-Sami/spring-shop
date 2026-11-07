package com.marzouki.shop.cart;

import com.marzouki.shop.catalog.ProductVariant;
import com.marzouki.shop.catalog.ProductVariantRepository;
import com.marzouki.shop.common.ConflictException;
import com.marzouki.shop.order.Coupon;
import com.marzouki.shop.order.CouponRepository;
import com.marzouki.shop.order.Pricing;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartService {

    private final ProductVariantRepository variants;
    private final CouponRepository coupons;

    /** Adds to the basket, never beyond the stock on hand or the per-line limit. */
    public void add(Cart cart, Long variantId, int quantity) {
        ProductVariant variant = variants.findPurchasableById(variantId)
                .orElseThrow(() -> new ConflictException("This product is no longer available"));
        if (variant.getStock() == 0) {
            throw new ConflictException(describe(variant) + " is sold out");
        }
        int wanted = cart.quantityOf(variantId) + Math.min(Math.max(quantity, 1), Cart.MAX_PER_LINE);
        cart.set(variantId, Math.min(wanted, Math.min(variant.getStock(), Cart.MAX_PER_LINE)));
    }

    public void setQuantity(Cart cart, Long variantId, int quantity) {
        if (quantity <= 0) {
            cart.remove(variantId);
            return;
        }
        ProductVariant variant = variants.findPurchasableById(variantId).orElse(null);
        if (variant == null) {
            cart.remove(variantId);
            return;
        }
        cart.set(variantId, Math.min(quantity, Math.min(variant.getStock(), Cart.MAX_PER_LINE)));
    }

    @Transactional
    public void applyCoupon(Cart cart, String code) {
        Coupon coupon = coupons.findByCodeIgnoreCase(code.trim())
                .orElseThrow(() -> new ConflictException("That coupon code is not valid"));
        String problem = coupon.problemAt(Instant.now());
        if (problem != null) {
            throw new ConflictException(problem);
        }
        cart.setCouponCode(coupon.getCode());
    }

    /** Prices the basket with today's data: removed products vanish, quantities shrink to the stock. */
    public CartView view(Cart cart) {
        Map<Long, Integer> wanted = cart.snapshot();
        Map<Long, ProductVariant> found = wanted.isEmpty() ? Map.of()
                : variants.findPurchasable(wanted.keySet()).stream()
                        .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));

        List<CartView.Line> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> entry : wanted.entrySet()) {
            ProductVariant variant = found.get(entry.getKey());
            if (variant == null || variant.getStock() == 0) {
                cart.remove(entry.getKey());
                continue;
            }
            int quantity = Math.min(entry.getValue(), variant.getStock());
            if (quantity != entry.getValue()) {
                cart.set(entry.getKey(), quantity);
            }
            BigDecimal unit = variant.getEffectivePrice();
            BigDecimal lineTotal = unit.multiply(BigDecimal.valueOf(quantity));
            lines.add(new CartView.Line(variant, quantity, unit, lineTotal, quantity < entry.getValue()));
            subtotal = subtotal.add(lineTotal);
        }

        com.marzouki.shop.catalog.ProductViews.warm(lines.stream().map(CartView.Line::product).distinct().toList());

        Coupon coupon = cart.getCouponCode() == null ? null
                : coupons.findByCodeIgnoreCase(cart.getCouponCode()).orElse(null);
        if (coupon != null && coupon.problemAt(Instant.now()) != null) {
            cart.setCouponCode(null);
            coupon = null;
        }
        BigDecimal discount = coupon == null ? BigDecimal.ZERO : Pricing.discount(subtotal, coupon.getPercentOff());
        return new CartView(lines, subtotal, discount, subtotal.subtract(discount),
                coupon == null ? null : coupon.getCode(), coupon == null ? null : coupon.getPercentOff());
    }

    private static String describe(ProductVariant variant) {
        String label = variant.getLabel();
        return variant.getProduct().getName() + (label == null ? "" : " (" + label + ")");
    }
}