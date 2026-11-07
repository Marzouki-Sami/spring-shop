package com.marzouki.shop.customer;

import com.marzouki.shop.common.Like;
import com.marzouki.shop.common.NotFoundException;
import com.marzouki.shop.order.Order;
import com.marzouki.shop.order.OrderRepository;
import com.marzouki.shop.user.User;
import com.marzouki.shop.user.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** Managers see who their customers are and what they bought. Staff accounts never appear here. */
@Controller
@RequiredArgsConstructor
public class CustomerController {

    private static final List<Integer> SIZES = List.of(10, 25, 50, 100);

    private final CustomerRepository customers;
    private final UserRepository users;
    private final OrderRepository orders;

    @GetMapping("/manage/customers")
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "name") String sort,
                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, Model model) {
        int pageSize = SIZES.contains(size) ? size : 25;
        PageRequest paging = PageRequest.of(Math.max(page, 0), pageSize);
        String pattern = Like.contains(q);
        String order = List.of("name", "newest", "spent", "orders").contains(sort) ? sort : "name";
        Page<CustomerRow> rows = switch (order) {
            case "newest" -> customers.byNewest(pattern, paging);
            case "spent" -> customers.bySpent(pattern, paging);
            case "orders" -> customers.byOrders(pattern, paging);
            default -> customers.byName(pattern, paging);
        };
        model.addAttribute("rows", rows);
        model.addAttribute("q", q.trim());
        model.addAttribute("sort", order);
        model.addAttribute("size", pageSize);
        model.addAttribute("sizes", SIZES);
        return "customer/customers";
    }

    @GetMapping("/manage/customers/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable Long id, Model model) {
        CustomerRow row = customers.summary(id).orElseThrow(() -> new NotFoundException("Customer", id));
        User user = users.findById(id).orElseThrow(() -> new NotFoundException("Customer", id));
        List<Order> history = orders.findTop50ByUserIdOrderByCreatedAtDescIdDesc(id);
        model.addAttribute("customer", row);
        model.addAttribute("user", user);
        model.addAttribute("orders", history);
        return "customer/customer";
    }
}