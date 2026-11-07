package com.marzouki.shop.seo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marzouki.shop.catalog.Product;
import com.marzouki.shop.catalog.ShopQuery;
import com.marzouki.shop.settings.SettingsService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Builds the search and sharing information for the public pages. */
@Service
@RequiredArgsConstructor
public class SeoService {

    private static final int DESCRIPTION_LENGTH = 160;

    private final SiteProperties site;
    private final SettingsService settings;
    private final ObjectMapper json;

    public String absolute(String path) {
        return site.url() + path;
    }

    public Seo home() {
        String shop = settings.shopName();
        return new Seo(shop + ": browse the latest products and order online.", absolute("/"), null, "website", false, null);
    }

    /** The shop listing. Any filter, sort or page beyond the first is a variation of the same list, so it stays out of search results. */
    public Seo shop(ShopQuery query, boolean firstPage) {
        boolean variation = query.isFiltered() || !query.getSort().equals("name") || !firstPage;
        return new Seo("Browse all products at " + settings.shopName() + ".", absolute("/products"), null, "website", variation, null);
    }

    public Seo product(Product product, Double averageRating, int reviewCount) {
        String canonical = absolute("/products/" + product.getId());
        String image = product.getPrimaryImage() == null ? null : absolute(product.getPrimaryImage().getUrl());
        String description = summary(product);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("@context", "https://schema.org");
        data.put("@type", "Product");
        data.put("name", product.getName());
        data.put("description", description);
        data.put("sku", product.getSku());
        if (!product.getImages().isEmpty()) {
            List<String> images = new ArrayList<>();
            product.getImages().forEach(i -> images.add(absolute(i.getUrl())));
            data.put("image", images);
        }
        Map<String, Object> offer = new LinkedHashMap<>();
        offer.put("@type", "Offer");
        offer.put("url", canonical);
        offer.put("priceCurrency", "EUR");
        BigDecimal price = product.getFromPrice();
        offer.put("price", price.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        offer.put("availability", product.getStock() > 0 ? "https://schema.org/InStock" : "https://schema.org/OutOfStock");
        data.put("offers", offer);
        if (averageRating != null && reviewCount > 0) {
            Map<String, Object> rating = new LinkedHashMap<>();
            rating.put("@type", "AggregateRating");
            rating.put("ratingValue", String.format(java.util.Locale.ROOT, "%.1f", averageRating));
            rating.put("reviewCount", reviewCount);
            data.put("aggregateRating", rating);
        }
        return new Seo(description, canonical, image, "product", false, safeJson(data));
    }

    private String safeJson(Map<String, Object> data) {
        try {
            return com.marzouki.shop.common.ScriptJson.escape(json.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String summary(Product product) {
        String text = product.getDescription() == null ? "" : product.getDescription().replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) {
            text = product.getName();
        }
        return text.length() <= DESCRIPTION_LENGTH ? text : text.substring(0, DESCRIPTION_LENGTH - 1).stripTrailing() + "…";
    }
}