package com.marzouki.shop.catalog;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One concrete thing a customer can buy: a product in a particular size, colour and so on. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "product_variants")
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(nullable = false, unique = true, length = 60)
    private String sku;

    /** When set, replaces the product's price for this variant only. */
    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int stock;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Sorted ids of the option values, e.g. "3-7". Unique per product; kept in step by {@link #syncOptionKey()}. */
    @Column(name = "option_key", nullable = false, length = 255)
    private String optionKey = "";

    @ManyToMany
    @JoinTable(name = "variant_option_values",
            joinColumns = @JoinColumn(name = "variant_id"),
            inverseJoinColumns = @JoinColumn(name = "option_value_id"))
    private Set<OptionValue> optionValues = new LinkedHashSet<>();

    @PrePersist
    @PreUpdate
    void syncOptionKey() {
        optionKey = optionValues.stream().map(OptionValue::getId).sorted().map(String::valueOf).collect(Collectors.joining("-"));
    }

    public BigDecimal getEffectivePrice() {
        return price != null ? price : product.getPrice();
    }

    /** "Size: M, Color: Black", or null for the default variant of a product without options. */
    public String getLabel() {
        if (optionValues.isEmpty()) {
            return null;
        }
        return optionValues.stream()
                .sorted(Comparator.comparing((OptionValue v) -> v.getOptionType().getId()))
                .map(v -> v.getOptionType().getName() + ": " + v.getLabel())
                .collect(Collectors.joining(", "));
    }

    /** Short form without option names: "M / Black". */
    public String getShortLabel() {
        return optionValues.stream()
                .sorted(Comparator.comparing((OptionValue v) -> v.getOptionType().getId()))
                .map(OptionValue::getLabel)
                .collect(Collectors.joining(" / "));
    }
}