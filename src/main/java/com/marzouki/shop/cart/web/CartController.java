package com.marzouki.shop.cart.web;

import com.marzouki.shop.cart.Cart;
import com.marzouki.shop.cart.CartService;
import com.marzouki.shop.common.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The basket. Open to everyone: guests can fill it, but must sign in to check out. */
@Controller
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {

    private final Cart cart;
    private final CartService carts;

    @GetMapping
    public String show(Model model) {
        model.addAttribute("cart", carts.view(cart));
        return "cart/cart";
    }

    @PostMapping("/add")
    public String add(@RequestParam Long variantId, @RequestParam(defaultValue = "1") int quantity,
                      RedirectAttributes flash) {
        try {
            int before = cart.quantityOf(variantId);
            carts.add(cart, variantId, quantity);
            int now = cart.quantityOf(variantId);
            if (now < before + Math.max(quantity, 1)) {
                flash.addFlashAttribute("warning", now >= Cart.MAX_PER_LINE
                        ? "You can order at most " + Cart.MAX_PER_LINE + " of one item at a time"
                        : "Only " + now + " available, so that is all your cart holds now");
            } else {
                flash.addFlashAttribute("success", "Added to your cart");
            }
        } catch (ConflictException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/cart";
    }

    @PostMapping("/update")
    public String update(@RequestParam Long variantId, @RequestParam int quantity) {
        carts.setQuantity(cart, variantId, quantity);
        return "redirect:/cart";
    }

    @PostMapping("/remove")
    public String remove(@RequestParam Long variantId) {
        cart.remove(variantId);
        return "redirect:/cart";
    }

    @PostMapping("/coupon")
    public String applyCoupon(@RequestParam String code, RedirectAttributes flash) {
        try {
            carts.applyCoupon(cart, code);
            flash.addFlashAttribute("success", "Coupon applied");
        } catch (ConflictException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/cart";
    }

    @PostMapping("/coupon/remove")
    public String removeCoupon() {
        cart.setCouponCode(null);
        return "redirect:/cart";
    }
}