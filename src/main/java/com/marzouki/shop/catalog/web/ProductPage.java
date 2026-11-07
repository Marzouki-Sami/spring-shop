package com.marzouki.shop.catalog.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marzouki.shop.catalog.OptionType;
import com.marzouki.shop.catalog.OptionValue;
import com.marzouki.shop.catalog.Product;
import com.marzouki.shop.catalog.ProductVariant;
import com.marzouki.shop.common.Money;
import java.util.Comparator;
import java.util.List;

/** Everything the product page needs to let a customer pick a variant: option groups and a JSON catalogue for the picker script. */
public record ProductPage(boolean optioned, List<Group> groups, Long initialVariantId, String variantsJson,
                          String initialPrice) {

    public record Choice(Long id, String label, String hex) {
    }

    public record Group(Long typeId, String name, boolean color, List<Choice> choices) {
    }

    record VariantInfo(Long id, String sku, List<Long> values, int stock, String price, String label) {
    }

    static ProductPage of(Product product, ObjectMapper mapper) {
        List<ProductVariant> active = product.getActiveVariants();
        ProductVariant initial = active.stream().filter(v -> v.getStock() > 0).findFirst()
                .orElse(active.isEmpty() ? null : active.get(0));

        List<Group> groups = List.of();
        if (product.isOptioned()) {
            groups = product.getProductType().getOptionTypes().stream().map(type -> new Group(type.getId(),
                    type.getName(), type.getDisplay() == OptionType.Display.COLOR,
                    type.getValues().stream()
                            .filter(value -> active.stream().anyMatch(v -> v.getOptionValues().contains(value)))
                            .map(value -> new Choice(value.getId(), value.getLabel(), value.getColorHex()))
                            .toList())).filter(group -> !group.choices().isEmpty()).toList();
        }
        List<VariantInfo> infos = active.stream().map(v -> new VariantInfo(v.getId(), v.getSku(),
                v.getOptionValues().stream().map(OptionValue::getId).sorted(Comparator.naturalOrder()).toList(),
                v.getStock(), Money.format(v.getEffectivePrice()), v.getLabel())).toList();
        try {
            // "<" is escaped so the data can never close its own script element.
            String json = com.marzouki.shop.common.ScriptJson.escape(mapper.writeValueAsString(infos));
            return new ProductPage(product.isOptioned(), groups, initial == null ? null : initial.getId(), json,
                    initial == null ? Money.format(product.getPrice()) : Money.format(initial.getEffectivePrice()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not describe the variants", e);
        }
    }
}