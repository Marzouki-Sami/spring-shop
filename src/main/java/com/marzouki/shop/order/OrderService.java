package com.marzouki.shop.order;

import com.marzouki.shop.cart.Cart;
import com.marzouki.shop.cart.CartService;
import com.marzouki.shop.cart.CartView;
import com.marzouki.shop.catalog.ProductVariant;
import com.marzouki.shop.catalog.ProductVariantRepository;
import com.marzouki.shop.common.ConflictException;
import com.marzouki.shop.common.NotFoundException;
import com.marzouki.shop.order.dto.ShippingForm;
import com.marzouki.shop.user.UserRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    /** At or below this many units a product triggers a low-stock alert. */
    public static final int LOW_STOCK_LEVEL = com.marzouki.shop.catalog.Product.DEFAULT_LOW_STOCK;

    private final OrderRepository orders;
    private final ProductVariantRepository variantRepository;
    private final CouponRepository coupons;
    private final StockMovementRepository movements;
    private final UserRepository users;
    private final CartService carts;
    private final org.springframework.context.ApplicationEventPublisher events;

    /**
     * Turns the basket into an order. Stock is taken line by line with atomic updates; if any line
     * cannot be filled the whole transaction rolls back and nothing is reserved.
     */
    @Transactional
    public Order place(Long userId, ShippingForm shipping, Cart cart) {
        CartView view = carts.view(cart);
        if (view.isEmpty()) {
            throw new ConflictException("Your cart is empty");
        }
        boolean changed = view.lines().stream().anyMatch(CartView.Line::limited)
                || (shipping.getExpectedTotal() != null && shipping.getExpectedTotal().compareTo(view.total()) != 0);
        if (changed) {
            throw new ConflictException("Prices or availability changed while you were checking out. "
                    + "Please review the updated total and confirm again.");
        }
        Coupon coupon = null;
        if (view.couponCode() != null) {
            coupon = coupons.findByCodeIgnoreCase(view.couponCode())
                    .orElseThrow(() -> new ConflictException("That coupon code is not valid"));
            if (coupons.claimUse(coupon.getId()) == 0) {
                throw new ConflictException("This coupon has been used up");
            }
        }

        Order order = new Order();
        order.setUser(users.getReferenceById(userId));
        order.setFullName(shipping.getFullName().trim());
        order.setAddress(shipping.getAddress().trim());
        order.setCity(shipping.getCity().trim());
        order.setPostalCode(shipping.getPostalCode().trim());
        order.setCountry(shipping.getCountry().trim());
        order.setPhone(shipping.getPhone() == null || shipping.getPhone().isBlank() ? null : shipping.getPhone().trim());
        boolean pinned = shipping.getLatitude() != null && shipping.getLongitude() != null;
        order.setLatitude(pinned ? shipping.getLatitude() : null);
        order.setLongitude(pinned ? shipping.getLongitude() : null);
        order.setCouponCode(view.couponCode());
        order.setSubtotal(view.subtotal());
        order.setDiscount(view.discount());
        order.setTotal(view.total());

        for (CartView.Line line : view.lines()) {
            ProductVariant variant = line.variant();
            if (variantRepository.takeStock(variant.getId(), line.quantity()) == 0) {
                throw new ConflictException("Sorry, " + line.product().getName() + " just went out of stock");
            }
            int before = variant.getStock();
            int level = line.product().getLowStockLevel();
            if (before > level && before - line.quantity() <= level) {
                events.publishEvent(new OrderEvents.LowStock(variant.getId()));
            }
            OrderItem item = new OrderItem();
            item.setProduct(line.product());
            item.setVariant(variant);
            item.setVariantLabel(variant.getLabel());
            item.setSku(variant.getSku());
            item.setName(line.product().getName());
            item.setUnitPrice(line.unitPrice());
            item.setQuantity(line.quantity());
            order.addItem(item);
        }
        orders.save(order);
        for (OrderItem item : order.getItems()) {
            movements.save(new StockMovement(item.getProduct().getId(), item.getVariant().getId(),
                    -item.getQuantity(), StockMovement.Reason.ORDER, order.getId()));
        }
        events.publishEvent(new OrderEvents.OrderPlaced(order.getId()));
        return order;
    }

    public Order get(Long id) {
        return orders.findWithItemsById(id).orElseThrow(() -> new NotFoundException("Order", id));
    }

    /** An order as seen by its owner; other people's orders look like they do not exist. */
    public Order getOwned(Long id, Long userId) {
        Order order = get(id);
        if (!order.getUser().getId().equals(userId)) {
            throw new NotFoundException("Order", id);
        }
        return order;
    }

    @Transactional
    public Order cancelOwn(Long id, Long userId) {
        Order order = getOwned(id, userId);
        if (!order.getStatus().customerCanCancel()) {
            throw new ConflictException("This order is already being prepared. Contact the shop to change it.");
        }
        return moveTo(order, OrderStatus.CANCELLED);
    }

    @Transactional
    public Order changeStatus(Long id, OrderStatus target) {
        return moveTo(get(id), target);
    }

    private Order moveTo(Order order, OrderStatus target) {
        if (!order.getStatus().canMoveTo(target)) {
            throw new ConflictException("An order that is " + order.getStatus().getLabel().toLowerCase()
                    + " cannot become " + target.getLabel().toLowerCase());
        }
        if (orders.transition(order.getId(), order.getStatus(), target) == 0) {
            throw new ConflictException("This order was just changed by someone else. Reload the page and try again.");
        }
        if (target == OrderStatus.CANCELLED) {
            for (OrderItem item : order.getItems()) {
                if (item.getVariant() != null) {
                    boolean wasSoldOut = item.getVariant().getStock() == 0;
                    variantRepository.returnStock(item.getVariant().getId(), item.getQuantity());
                    if (wasSoldOut) {
                        events.publishEvent(new OrderEvents.BackInStock(item.getVariant().getId()));
                    }
                    movements.save(new StockMovement(item.getProduct() == null ? null : item.getProduct().getId(),
                            item.getVariant().getId(), item.getQuantity(), StockMovement.Reason.CANCELLATION,
                            order.getId()));
                }
            }
            if (order.getCouponCode() != null) {
                coupons.findByCodeIgnoreCase(order.getCouponCode()).ifPresent(c -> coupons.releaseUse(c.getId()));
            }
        }
        order.setStatus(target);
        if (target == OrderStatus.SHIPPED) {
            events.publishEvent(new OrderEvents.OrderShipped(order.getId()));
        }
        return order;
    }

    @Transactional
    public Coupon createCoupon(String code, int percentOff, Instant expiresAt, Integer maxUses) {
        String normalized = code.trim().toUpperCase();
        if (coupons.existsByCodeIgnoreCase(normalized)) {
            throw new ConflictException("The coupon code " + normalized + " already exists");
        }
        Coupon coupon = new Coupon();
        coupon.setCode(normalized);
        coupon.setPercentOff(percentOff);
        coupon.setExpiresAt(expiresAt);
        coupon.setMaxUses(maxUses);
        return coupons.save(coupon);
    }

    @Transactional
    public void setCouponActive(Long id, boolean active) {
        coupons.findById(id).orElseThrow(() -> new NotFoundException("Coupon", id)).setActive(active);
    }

    /** One page of the back-office order list. A number in the search box also matches the order number. */
    public org.springframework.data.domain.Page<Order> search(com.marzouki.shop.common.ListQuery query) {
        return search(query, null);
    }

    /** The same list limited to one customer's own orders when a user id is given. */
    public org.springframework.data.domain.Page<Order> search(com.marzouki.shop.common.ListQuery query, Long onlyUserId) {
        org.springframework.data.jpa.domain.Specification<Order> text = com.marzouki.shop.common.Specs.<Order>text(query.like(),
                "user.firstName", "user.lastName", "user.email", "fullName", "city");
        if (query.getQ().matches("[0-9]{1,15}")) {
            long number = Long.parseLong(query.getQ());
            text = text.or((r, q, cb) -> cb.equal(r.get("id"), number));
        }
        org.springframework.data.jpa.domain.Specification<Order> status = com.marzouki.shop.common.Specs.none();
        try {
            if (!query.getStatus().isEmpty()) {
                OrderStatus wanted = OrderStatus.valueOf(query.getStatus());
                status = (r, q, cb) -> cb.equal(r.get("status"), wanted);
            }
        } catch (IllegalArgumentException ignored) {
            // an unknown status shows everything
        }
        org.springframework.data.domain.Sort sort = switch (query.getSort()) {
            case "oldest" -> org.springframework.data.domain.Sort.by("createdAt").ascending().and(org.springframework.data.domain.Sort.by("id"));
            case "total-desc" -> org.springframework.data.domain.Sort.by("total").descending().and(org.springframework.data.domain.Sort.by("id").descending());
            case "total-asc" -> org.springframework.data.domain.Sort.by("total").ascending().and(org.springframework.data.domain.Sort.by("id"));
            default -> org.springframework.data.domain.Sort.by("createdAt").descending().and(org.springframework.data.domain.Sort.by("id").descending());
        };
        org.springframework.data.jpa.domain.Specification<Order> owner = onlyUserId == null ? com.marzouki.shop.common.Specs.none()
                : (r, q, cb) -> cb.equal(r.get("user").get("id"), onlyUserId);
        return orders.findAll(text.and(status).and(owner), query.pageRequest(sort));
    }

    public org.springframework.data.domain.Page<Coupon> couponPage(com.marzouki.shop.common.ListQuery query) {
        Instant now = Instant.now();
        org.springframework.data.jpa.domain.Specification<Coupon> status = switch (query.getStatus()) {
            case "USABLE" -> (r, q, cb) -> cb.and(cb.isTrue(r.get("active")),
                    cb.or(cb.isNull(r.get("expiresAt")), cb.greaterThan(r.<Instant>get("expiresAt"), now)),
                    cb.or(cb.isNull(r.get("maxUses")), cb.lessThan(r.<Integer>get("usedCount"), r.<Integer>get("maxUses"))));
            case "DISABLED" -> (r, q, cb) -> cb.isFalse(r.get("active"));
            case "EXPIRED" -> (r, q, cb) -> cb.and(cb.isNotNull(r.get("expiresAt")), cb.lessThanOrEqualTo(r.<Instant>get("expiresAt"), now));
            default -> com.marzouki.shop.common.Specs.none();
        };
        org.springframework.data.domain.Sort sort = switch (query.getSort()) {
            case "code" -> org.springframework.data.domain.Sort.by("code").ascending();
            case "used" -> org.springframework.data.domain.Sort.by("usedCount").descending().and(org.springframework.data.domain.Sort.by("id"));
            default -> org.springframework.data.domain.Sort.by("createdAt").descending().and(org.springframework.data.domain.Sort.by("id").descending());
        };
        return coupons.findAll(com.marzouki.shop.common.Specs.<Coupon>text(query.like(), "code").and(status), query.pageRequest(sort));
    }
}