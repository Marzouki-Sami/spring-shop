package com.marzouki.shop.catalog.web;

import com.marzouki.shop.catalog.CategoryRepository;
import com.marzouki.shop.catalog.Product;
import com.marzouki.shop.catalog.ProductViews;
import com.marzouki.shop.catalog.ProductRepository;
import com.marzouki.shop.catalog.ProductService;
import com.marzouki.shop.catalog.ShopQuery;
import com.marzouki.shop.catalog.Subcategory;
import com.marzouki.shop.catalog.SubcategoryRepository;
import com.marzouki.shop.common.NotFoundException;
import com.marzouki.shop.engagement.EngagementService;
import com.marzouki.shop.engagement.dto.ReviewForm;
import com.marzouki.shop.security.AppUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

/** The public shop window: anyone can browse, no account needed. */
@Controller
@RequiredArgsConstructor
public class StorefrontController {


    private final ProductRepository products;
    private final ProductService productService;
    private final com.marzouki.shop.seo.SeoService seo;
    private final com.marzouki.shop.catalog.RelatedProducts relatedProducts;
    private final SubcategoryRepository subcategoryRepository;
    private final CategoryRepository categories;
    private final EngagementService engagement;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    @GetMapping("/")
    @Transactional(readOnly = true)
    public String home(Model model) {
        java.util.List<Product> latest = products.findTop8ByActiveTrueOrderByCreatedAtDescIdDesc();
        ProductViews.warm(latest);
        model.addAttribute("latest", latest);
        model.addAttribute("seo", seo.home());
        model.addAttribute("categories", categories.findAllByOrderByTitleAsc());
        return "storefront/home";
    }

    @GetMapping("/products")
    @Transactional(readOnly = true)
    public String browse(@ModelAttribute("query") ShopQuery query, Model model) {
        Page<Product> result = productService.shop(query);
        model.addAttribute("page", result);
        model.addAttribute("links", new com.marzouki.shop.common.PageLinks("/products", query.params()));
        model.addAttribute("categories", categories.findAllByOrderByTitleAsc());
        model.addAttribute("subcategories", query.getCategory() == null ? java.util.List.<Subcategory>of()
                : subcategoryRepository.findByCategoryIdOrderByTitleAsc(query.getCategory()));
        model.addAttribute("sorts", ShopQuery.SORTS);
        model.addAttribute("seo", seo.shop(query, result.isFirst()));
        return "storefront/products";
    }
    @GetMapping("/products/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me, Model model) {
        Product product = products.findByIdAndActiveTrue(id).orElseThrow(() -> new NotFoundException("Product", id));
        ProductViews.warm(java.util.List.of(product));
        model.addAttribute("product", product);
        model.addAttribute("page", ProductPage.of(product, json));
        model.addAttribute("related", relatedProducts.forProduct(id));
        var reviews = engagement.reviewsFor(id);
        var average = engagement.averageRating(id);
        model.addAttribute("reviews", reviews);
        model.addAttribute("average", average);
        model.addAttribute("seo", seo.product(product, average, reviews.size()));
        model.addAttribute("reviewForm", new ReviewForm());
        if (me != null) {
            model.addAttribute("wished", engagement.isWished(me.getId(), id));
            model.addAttribute("canReview", engagement.hasBought(me.getId(), id));
            model.addAttribute("myReview", engagement.reviewBy(me.getId(), id));
        }
        return "storefront/product";
    }
}