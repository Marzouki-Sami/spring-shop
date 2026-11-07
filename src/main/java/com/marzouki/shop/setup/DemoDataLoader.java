package com.marzouki.shop.setup;

import com.marzouki.shop.catalog.Category;
import com.marzouki.shop.catalog.CategoryRepository;
import com.marzouki.shop.catalog.OptionType;
import com.marzouki.shop.catalog.OptionTypeRepository;
import com.marzouki.shop.catalog.OptionValue;
import com.marzouki.shop.catalog.Product;
import com.marzouki.shop.catalog.ProductRepository;
import com.marzouki.shop.catalog.ProductType;
import com.marzouki.shop.catalog.ProductTypeRepository;
import com.marzouki.shop.catalog.ProductVariant;
import com.marzouki.shop.catalog.ProductVariantRepository;
import com.marzouki.shop.catalog.VariantService;
import com.marzouki.shop.order.StockMovement;
import com.marzouki.shop.order.StockService;
import com.marzouki.shop.catalog.Subcategory;
import com.marzouki.shop.catalog.SubcategoryRepository;
import com.marzouki.shop.catalog.Supplier;
import com.marzouki.shop.catalog.SupplierRepository;
import com.marzouki.shop.user.Role;
import com.marzouki.shop.user.UserRepository;
import com.marzouki.shop.user.UserService;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fills an empty database with a believable shop, for demos and screenshots. Only runs with the
 * {@code demo} profile. Account passwords are random and printed once to the log.
 */
@Slf4j
@Component
@Profile("demo")
@RequiredArgsConstructor
public class DemoDataLoader implements ApplicationRunner {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserService users;
    private final UserRepository userRepository;
    private final CategoryRepository categories;
    private final SubcategoryRepository subcategories;
    private final SupplierRepository suppliers;
    private final ProductRepository products;
    private final com.marzouki.shop.order.OrderService orders;
    private final com.marzouki.shop.cart.CartService carts;
    private final com.marzouki.shop.engagement.EngagementService engagement;
    private final ProductVariantRepository variants;
    private final OptionTypeRepository optionTypes;
    private final ProductTypeRepository productTypes;
    private final VariantService variantService;
    private final StockService stock;
    private final DemoImageFactory imageFactory;
    private final com.marzouki.shop.media.ProductImageService imageService;

    @org.springframework.beans.factory.annotation.Value("${app.site.url}")
    private String siteUrl;

    /** The demo accounts have no two-factor sign-in, so the demo profile must never face the internet. */
    private void requireLocalAddress() {
        String host = java.net.URI.create(siteUrl).getHost();
        if (!"localhost".equals(host) && !"127.0.0.1".equals(host)) {
            throw new IllegalStateException("The demo profile only runs on a localhost address, but APP_BASE_URL is " + siteUrl);
        }
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        requireLocalAddress();
        if (userRepository.count() > 0 || products.count() > 0) {
            return;
        }
        createAccount("Ada", "Admin", "admin@demo.shop", Role.ADMIN);
        createAccount("Max", "Manager", "manager@demo.shop", Role.MANAGER);
        createAccount("Cleo", "Customer", "customer@demo.shop", Role.CUSTOMER);

        Supplier nordic = supplier("Nordic Goods AB", "Elin Berg", "orders@nordic-goods.example");
        Supplier atelier = supplier("Atelier Lumi", "Jules Martin", "hello@atelier-lumi.example");

        Subcategory laptops = sub("Computers", "Laptops");
        Subcategory audio = sub("Electronics", "Audio");
        Subcategory wearables = sub("Electronics", "Wearables");
        Subcategory lighting = sub("Home", "Lighting");
        Subcategory kitchen = sub("Home", "Kitchen");
        Subcategory bags = sub("Fashion", "Bags");
        Subcategory shoes = sub("Fashion", "Shoes");

        product("LAP-001", "Aero 14 Ultrabook", "Light 14-inch laptop with a bright display and all-day battery.", "1199.00", 12, laptops, nordic);
        product("LAP-002", "Forge 16 Workstation", "Powerful 16-inch machine for developers and creators.", "1899.00", 5, laptops, nordic);
        product("AUD-001", "Halo Wireless Headphones", "Over-ear headphones with active noise cancelling.", "249.90", 30, audio, nordic);
        product("AUD-002", "Pebble Bluetooth Speaker", "Pocket-sized speaker with 12 hours of playtime.", "59.00", 48, audio, nordic);
        product("WEA-001", "Pulse Fitness Watch", "Tracks sleep, heart rate and workouts.", "179.00", 22, wearables, nordic);
        product("WEA-002", "Orbit Smart Ring", "Discreet health tracking for everyday wear.", "299.00", 0, wearables, nordic);
        product("LGT-001", "Lumi Desk Lamp", "Dimmable LED lamp with a warm glow.", "49.50", 40, lighting, atelier);
        product("LGT-002", "Arc Floor Lamp", "Tall brass-finish lamp for reading corners.", "139.00", 9, lighting, atelier);
        product("KIT-001", "Stoneware Mug Set", "Four hand-glazed mugs.", "42.00", 25, kitchen, atelier);
        product("KIT-002", "Cast Iron Skillet", "Pre-seasoned 26 cm skillet that lasts a lifetime.", "64.00", 14, kitchen, atelier);
        product("BAG-001", "Voyager Backpack", "Water-resistant 24 L backpack with a laptop sleeve.", "89.00", 33, bags, atelier);
        product("BAG-002", "Harbor Tote", "Waxed canvas tote for daily errands.", "54.00", 18, bags, atelier);
        product("SHO-001", "Trail Runner Sneakers", "Grippy, breathable sneakers for city and trail.", "119.00", 27, shoes, atelier);
        product("SHO-002", "Classic Leather Boots", "Handmade boots in full-grain leather.", "199.00", 4, shoes, atelier);
        Subcategory tops = sub("Fashion", "Tops");

        Map<String, String> sizes = new LinkedHashMap<>();
        for (String label : List.of("S", "M", "L", "XL")) {
            sizes.put(label, null);
        }
        Map<String, String> shoeSizes = new LinkedHashMap<>();
        for (int n = 40; n <= 45; n++) {
            shoeSizes.put(String.valueOf(n), null);
        }
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("Black", "#111827");
        colors.put("Navy", "#1e3a8a");
        colors.put("Olive", "#4d5d2f");
        colors.put("Sand", "#d6c3a1");
        OptionType size = optionType("Size", OptionType.Display.TEXT, sizes);
        OptionType shoeSize = optionType("Shoe size", OptionType.Display.TEXT, shoeSizes);
        OptionType color = optionType("Color", OptionType.Display.COLOR, colors);
        ProductType clothing = productType("Clothing", size, color);
        ProductType footwear = productType("Footwear", shoeSize, color);

        typedProduct("TEE-001", "Classic Cotton Tee", "Soft, heavyweight organic cotton in three colours.", "24.00", tops, atelier,
                clothing, Map.of(size, List.of("S", "M", "L", "XL"), color, List.of("Black", "Navy", "Olive")));
        typedProduct("HOO-001", "Everyday Hoodie", "Brushed fleece hoodie with a roomy hood.", "59.00", tops, atelier,
                clothing, Map.of(size, List.of("S", "M", "L"), color, List.of("Black", "Sand")));
        typedProduct("SHO-003", "Court Sneakers", "Clean leather sneakers that go with everything.", "89.00", shoes, atelier,
                footwear, Map.of(shoeSize, List.of("40", "41", "42", "43", "44"), color, List.of("Black", "Sand")));
        orders.createCoupon("WELCOME10", 10, null, null);
        seedActivity();
        log.info("Demo data created. Sign in with admin@demo.shop, manager@demo.shop or customer@demo.shop using the passwords printed to the console above. Coupon WELCOME10 gives 10% off.");
    }

    /** A few customers with orders and reviews, so the dashboard and product pages are not empty. */
    private void seedActivity() {
        var cleo = userRepository.findByEmailIgnoreCase("customer@demo.shop").orElseThrow();
        var noor = users.create("Noor", "Haddad", "noor@demo.shop", randomPassword(), Role.CUSTOMER);
        var lena = users.create("Lena", "Fischer", "lena@demo.shop", randomPassword(), Role.CUSTOMER);

        place(cleo, "AUD-001", 1, com.marzouki.shop.order.OrderStatus.DELIVERED);
        place(cleo, "LGT-001", 2, com.marzouki.shop.order.OrderStatus.SHIPPED);
        place(noor, "AUD-001", 1, com.marzouki.shop.order.OrderStatus.DELIVERED);
        place(noor, "BAG-001", 1, com.marzouki.shop.order.OrderStatus.PROCESSING);
        place(lena, "LAP-001", 1, com.marzouki.shop.order.OrderStatus.PLACED);
        place(noor, "TEE-001-M-BLACK", 2, com.marzouki.shop.order.OrderStatus.PLACED);
        place(lena, "AUD-001", 1, com.marzouki.shop.order.OrderStatus.DELIVERED);

        review(cleo, "AUD-001", 5, "Great sound and the noise cancelling really works. Battery lasts for days.");
        review(noor, "AUD-001", 4, "Comfortable for long calls. The case feels a little plasticky.");
        review(lena, "AUD-001", 5, null);
        review(cleo, "LGT-001", 5, "Warm light, easy to dim.");
    }

    private void place(com.marzouki.shop.user.User buyer, String sku, int quantity,
                       com.marzouki.shop.order.OrderStatus target) {
        var cart = new com.marzouki.shop.cart.Cart();
        carts.add(cart, variantBySku(sku).getId(), quantity);
        var address = new com.marzouki.shop.order.dto.ShippingForm();
        address.setFullName(buyer.getFullName());
        address.setAddress("12 Example Street");
        address.setCity("Lyon");
        address.setPostalCode("69001");
        address.setCountry("France");
        var order = orders.place(buyer.getId(), address, cart);
        var path = java.util.List.of(com.marzouki.shop.order.OrderStatus.PROCESSING,
                com.marzouki.shop.order.OrderStatus.SHIPPED, com.marzouki.shop.order.OrderStatus.DELIVERED);
        for (var step : path) {
            if (order.getStatus() == target) {
                break;
            }
            orders.changeStatus(order.getId(), step);
            order.setStatus(step);
        }
    }

    private void review(com.marzouki.shop.user.User author, String sku, int rating, String comment) {
        var form = new com.marzouki.shop.engagement.dto.ReviewForm();
        form.setRating(rating);
        form.setComment(comment);
        engagement.submit(author.getId(), products.findAll().stream().filter(p -> p.getSku().equals(sku)).findFirst().orElseThrow().getId(), form);
    }

    private void createAccount(String first, String last, String email, Role role) {
        String generated = randomPassword();
        users.create(first, last, email, generated, role);
        // Printed to the console, not the log: it is a one-off credential for a local demo database.
        System.out.printf("Demo account %s (%s): %s%n", email, role, generated);
    }

    /** Meets the password policy: one character from each class plus random filler. */
    private static String randomPassword() {
        String[] pools = {"ABCDEFGHJKLMNPQRSTUVWXYZ", "abcdefghijkmnopqrstuvwxyz", "23456789", "!@#$%^&*-_+="};
        StringBuilder out = new StringBuilder();
        for (String pool : pools) {
            out.append(pool.charAt(RANDOM.nextInt(pool.length())));
        }
        String all = String.join("", pools);
        for (int i = 0; i < 12; i++) {
            out.append(all.charAt(RANDOM.nextInt(all.length())));
        }
        return out.toString();
    }

    private Supplier supplier(String company, String contact, String email) {
        Supplier supplier = new Supplier();
        supplier.setCompany(company);
        supplier.setContactName(contact);
        supplier.setEmail(email);
        return suppliers.save(supplier);
    }

    private Subcategory sub(String categoryTitle, String title) {
        Category category = categories.findAll().stream()
                .filter(c -> c.getTitle().equals(categoryTitle)).findFirst()
                .orElseGet(() -> {
                    Category created = new Category();
                    created.setTitle(categoryTitle);
                    return categories.save(created);
                });
        return subcategories.findAll().stream()
                .filter(s -> s.getTitle().equals(title) && s.getCategory().getId().equals(category.getId()))
                .findFirst()
                .orElseGet(() -> {
                    Subcategory created = new Subcategory();
                    created.setTitle(title);
                    created.setCategory(category);
                    return subcategories.save(created);
                });
    }

    private Product product(String sku, String name, String description, String price, int stock,
                            Subcategory subcategory, Supplier supplier) {
        Product product = newProduct(sku, name, description, price, subcategory, supplier);
        ProductVariant variant = product.addVariant(sku, 0);
        products.saveAndFlush(product);
        stockIn(variant, stock);
        pictures(product);
        return product;
    }

    private Product newProduct(String sku, String name, String description, String price,
                               Subcategory subcategory, Supplier supplier) {
        Product product = new Product();
        product.setSku(sku);
        product.setName(name);
        product.setDescription(description);
        product.setPrice(new BigDecimal(price));
        product.setSubcategory(subcategory);
        product.setSupplier(supplier);
        return product;
    }

    private void stockIn(ProductVariant variant, int quantity) {
        if (quantity > 0) {
            stock.adjust(variant.getId(), quantity, StockMovement.Reason.INITIAL, null);
        }
    }

    /** A product sold in sizes and colours: variants are generated, then given some stock. */
    private void typedProduct(String sku, String name, String description, String price, Subcategory subcategory,
                              Supplier supplier, ProductType type, Map<OptionType, List<String>> offered) {
        Product product = newProduct(sku, name, description, price, subcategory, supplier);
        product.setProductType(type);
        products.saveAndFlush(product);
        Map<Long, Set<Long>> chosen = new HashMap<>();
        offered.forEach((optionType, labels) -> chosen.put(optionType.getId(), optionType.getValues().stream()
                .filter(v -> labels.contains(v.getLabel())).map(OptionValue::getId).collect(Collectors.toSet())));
        variantService.generate(product.getId(), chosen);
        Product loaded = variantService.productWithVariants(product.getId());
        int index = 0;
        for (ProductVariant variant : loaded.getVariants()) {
            // Spread the stock so some sizes are plentiful, some low and a few sold out.
            int[] pattern = {14, 9, 22, 3, 0, 17, 6, 11};
            stockIn(variant, pattern[index++ % pattern.length]);
        }
        pictures(loaded);
    }

    /** Two to four generated pictures per product, so the gallery has something to page through. */
    private void pictures(Product product) {
        int count = 2 + (int) (product.getId() % 3);
        for (int i = 0; i < count; i++) {
            imageService.add(product.getId(), imageFactory.generate(product.getId().intValue(), i), product.getName() + ", view " + (i + 1));
        }
    }

    private ProductType productType(String name, OptionType... allowed) {
        ProductType type = new ProductType();
        type.setName(name);
        type.getOptionTypes().addAll(List.of(allowed));
        return productTypes.save(type);
    }

    private OptionType optionType(String name, OptionType.Display display, Map<String, String> values) {
        OptionType type = new OptionType();
        type.setName(name);
        type.setDisplay(display);
        values.forEach(type::addValue);
        return optionTypes.save(type);
    }

    private ProductVariant variantBySku(String sku) {
        return variants.findBySku(sku).orElseThrow();
    }
}