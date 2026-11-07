package com.marzouki.shop.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.marzouki.shop.catalog.dto.BulkVariantForm;
import com.marzouki.shop.catalog.dto.CategoryForm;
import com.marzouki.shop.catalog.dto.OptionTypeForm;
import com.marzouki.shop.catalog.dto.OptionValueForm;
import com.marzouki.shop.catalog.dto.ProductForm;
import com.marzouki.shop.catalog.dto.ProductTypeForm;
import com.marzouki.shop.catalog.dto.SubcategoryForm;
import com.marzouki.shop.common.ConflictException;
import com.marzouki.shop.order.StockMovement;
import com.marzouki.shop.order.StockMovementRepository;
import com.marzouki.shop.security.AppUserDetails;
import com.marzouki.shop.support.TestPasswords;
import com.marzouki.shop.user.Role;
import com.marzouki.shop.user.User;
import com.marzouki.shop.user.UserRepository;
import com.marzouki.shop.user.UserService;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** The variants workspace: paging, filtering, bulk actions, duplicate protection and generated SKUs. */
@SpringBootTest
@AutoConfigureMockMvc
class VariantWorkspaceTest {

    @Autowired MockMvc mvc;
    @Autowired OptionService optionService;
    @Autowired ProductTypeService typeService;
    @Autowired ProductService productService;
    @Autowired VariantService variantService;
    @Autowired CategoryService categoryService;
    @Autowired SubcategoryService subcategoryService;
    @Autowired UserService users;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ProductTypeRepository productTypes;
    @Autowired OptionTypeRepository optionTypes;
    @Autowired SubcategoryRepository subcategories;
    @Autowired CategoryRepository categories;
    @Autowired StockMovementRepository movements;
    @Autowired EntityManagerFactory emf;
    @Autowired TransactionTemplate tx;

    OptionType size;
    OptionType color;
    Product tee;
    Subcategory subcategory;
    ProductType clothing;
    User manager;

    @BeforeEach
    void data() {
        size = optionService.createType(optionForm("Size", OptionType.Display.TEXT));
        color = optionService.createType(optionForm("Color", OptionType.Display.COLOR));
        for (String label : List.of("S", "M", "L", "XL")) {
            optionService.addValue(size.getId(), value(label, null));
        }
        optionService.addValue(color.getId(), value("Black", "#111111"));
        optionService.addValue(color.getId(), value("Sand", "#d6c3a1"));
        ProductTypeForm typeForm = new ProductTypeForm();
        typeForm.setName("Clothing");
        typeForm.setOptionTypeIds(List.of(size.getId(), color.getId()));
        clothing = typeService.create(typeForm);

        CategoryForm categoryForm = new CategoryForm();
        categoryForm.setTitle("Fashion");
        Category category = categoryService.create(categoryForm);
        SubcategoryForm sub = new SubcategoryForm();
        sub.setTitle("Tops");
        sub.setCategoryId(category.getId());
        subcategory = subcategoryService.create(sub);

        tee = newProduct("TEE", "Cotton tee");
        manager = users.create("Mo", "Manager", "mo@example.com", TestPasswords.strong(), Role.MANAGER);
        variantService.generate(tee.getId(), allValues());
    }

    @AfterEach
    void cleanUp() {
        movements.deleteAll();
        productRepository.deleteAll();
        productTypes.deleteAll();
        optionTypes.deleteAll();
        subcategories.deleteAll();
        categories.deleteAll();
        userRepository.deleteAll();
    }

    private Product newProduct(String sku, String name) {
        ProductForm form = new ProductForm();
        form.setSku(sku);
        form.setName(name);
        form.setPrice(new BigDecimal("24.00"));
        form.setSubcategoryId(subcategory.getId());
        form.setProductTypeId(clothing.getId());
        return productService.create(form);
    }

    private OptionTypeForm optionForm(String name, OptionType.Display display) {
        OptionTypeForm form = new OptionTypeForm();
        form.setName(name);
        form.setDisplay(display);
        return form;
    }

    private OptionValueForm value(String label, String hex) {
        OptionValueForm form = new OptionValueForm();
        form.setLabel(label);
        form.setColorHex(hex);
        return form;
    }

    private Map<Long, Set<Long>> allValues() {
        return Map.of(size.getId(), idsOf("Size"), color.getId(), idsOf("Color"));
    }

    private Set<Long> idsOf(String typeName) {
        return optionService.findAll().stream().filter(t -> t.getName().equals(typeName))
                .flatMap(t -> t.getValues().stream()).map(OptionValue::getId).collect(Collectors.toSet());
    }

    private VariantQuery query(String q, String status, int size) {
        VariantQuery query = new VariantQuery();
        query.setQ(q);
        query.setStatus(status);
        query.setSize(size);
        return query;
    }

    private List<ProductVariant> all() {
        return variantService.overview(tee.getId(), query("", "ALL", 100)).page().getContent();
    }

    private BulkVariantForm bulk(BulkVariantForm.Action action, List<Long> ids) {
        BulkVariantForm form = new BulkVariantForm();
        form.setAction(action);
        form.setIds(ids);
        return form;
    }

    private List<Long> idsOfFirst(int n) {
        return all().stream().limit(n).map(ProductVariant::getId).toList();
    }

    @Test
    void theTablePagesFiltersAndSearchesOnTheServer() {
        assertThat(variantService.overview(tee.getId(), query("", "ALL", 10)).page().getTotalElements()).isEqualTo(8);
        var small = variantService.overview(tee.getId(), query("", "ALL", 25));
        assertThat(small.totalVariants()).isEqualTo(8);

        assertThat(variantService.overview(tee.getId(), query("sand", "ALL", 25)).page().getTotalElements()).isEqualTo(4);
        assertThat(variantService.overview(tee.getId(), query("TEE-XL", "ALL", 25)).page().getTotalElements()).isEqualTo(2);
        assertThat(variantService.overview(tee.getId(), query("%", "ALL", 25)).page().getTotalElements()).isZero();
        assertThat(variantService.overview(tee.getId(), query("_", "ALL", 25)).page().getTotalElements()).isZero();
        assertThat(variantService.overview(tee.getId(), query("", "OUT", 25)).page().getTotalElements()).isEqualTo(8);
        assertThat(variantService.overview(tee.getId(), query("", "LOW", 25)).page().getTotalElements()).isZero();
        assertThat(variantService.overview(tee.getId(), query("", "nonsense", 25)).page().getTotalElements()).isEqualTo(8);

        VariantQuery beyond = query("", "ALL", 10);
        beyond.setPage(99);
        assertThat(variantService.overview(tee.getId(), beyond).page().getContent()).hasSize(8);
        assertThat(query("", "ALL", 7).getSize()).isEqualTo(VariantQuery.DEFAULT_SIZE);
    }

    @Test
    void theDatabaseRefusesTwoVariantsWithTheSameOptions() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            Product product = productRepository.findById(tee.getId()).orElseThrow();
            ProductVariant existing = product.getVariants().get(0);
            ProductVariant copy = product.addVariant("COPY-1", 0);
            copy.getOptionValues().addAll(existing.getOptionValues());
            productRepository.flush();
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(all()).hasSize(8);
    }

    @Test
    void generatingTwiceNeverDuplicates() {
        VariantService.Generated again = variantService.generate(tee.getId(), allValues());
        assertThat(again.created()).isZero();
        assertThat(again.alreadyExisted()).isEqualTo(8);
        assertThat(all()).hasSize(8);
    }

    @Test
    void bulkPricingAndActivationApplyToTheTickedVariantsOnly() {
        List<Long> two = idsOfFirst(2);
        var done = variantService.bulk(tee.getId(), new VariantQuery(), withPrice(bulk(BulkVariantForm.Action.SET_PRICE, two), "19.50"));
        assertThat(done.changed()).isEqualTo(2);
        assertThat(all().stream().filter(v -> v.getPrice() != null)).hasSize(2);

        variantService.bulk(tee.getId(), new VariantQuery(), bulk(BulkVariantForm.Action.CLEAR_PRICE, two));
        assertThat(all().stream().filter(v -> v.getPrice() != null)).isEmpty();

        assertThat(variantService.bulk(tee.getId(), new VariantQuery(), bulk(BulkVariantForm.Action.DEACTIVATE, two)).changed()).isEqualTo(2);
        var again = variantService.bulk(tee.getId(), new VariantQuery(), bulk(BulkVariantForm.Action.DEACTIVATE, two));
        assertThat(again.changed()).isZero();
        assertThat(again.skipped()).isEqualTo(2);
        assertThat(all().stream().filter(v -> !v.isActive())).hasSize(2);
    }

    private BulkVariantForm withPrice(BulkVariantForm form, String price) {
        form.setPrice(new BigDecimal(price));
        return form;
    }

    @Test
    void bulkStockChangesAreLoggedAndNeverGoBelowZero() {
        List<Long> ids = idsOfFirst(3);
        BulkVariantForm add = bulk(BulkVariantForm.Action.ADJUST_STOCK, ids);
        add.setDelta(10);
        assertThat(variantService.bulk(tee.getId(), new VariantQuery(), add).changed()).isEqualTo(3);
        assertThat(movements.count()).isEqualTo(3);

        BulkVariantForm remove = bulk(BulkVariantForm.Action.ADJUST_STOCK, idsOfFirst(4));
        remove.setDelta(-5);
        var result = variantService.bulk(tee.getId(), new VariantQuery(), remove);
        assertThat(result.changed()).isEqualTo(3);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(all().stream().mapToInt(ProductVariant::getStock).sum()).isEqualTo(15);

        BulkVariantForm zero = bulk(BulkVariantForm.Action.ADJUST_STOCK, ids);
        zero.setDelta(0);
        assertThatThrownBy(() -> variantService.bulk(tee.getId(), new VariantQuery(), zero)).isInstanceOf(ConflictException.class);

        BulkVariantForm sale = bulk(BulkVariantForm.Action.ADJUST_STOCK, ids);
        sale.setDelta(1);
        sale.setReason(StockMovement.Reason.ORDER);
        assertThatThrownBy(() -> variantService.bulk(tee.getId(), new VariantQuery(), sale)).isInstanceOf(ConflictException.class);
    }

    @Test
    void theFilteredScopeAppliesToEverythingTheSearchMatches() {
        BulkVariantForm form = bulk(BulkVariantForm.Action.DEACTIVATE, List.of());
        form.setScope(BulkVariantForm.Scope.FILTERED);
        var done = variantService.bulk(tee.getId(), query("sand", "ALL", 10), form);
        assertThat(done.changed()).isEqualTo(4);
        assertThat(all().stream().filter(v -> !v.isActive()).allMatch(v -> v.getShortLabel().contains("Sand"))).isTrue();
    }

    @Test
    void bulkDeleteRemovesVariantsAndIgnoresVariantsOfOtherProducts() {
        Product other = newProduct("HAT", "Wool hat");
        variantService.generate(other.getId(), allValues());
        List<Long> foreign = variantService.overview(other.getId(), query("", "ALL", 100)).page().map(ProductVariant::getId).getContent();

        assertThatThrownBy(() -> variantService.bulk(tee.getId(), new VariantQuery(), bulk(BulkVariantForm.Action.DELETE, foreign)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("No variants selected");

        var done = variantService.bulk(tee.getId(), new VariantQuery(), bulk(BulkVariantForm.Action.DELETE, idsOfFirst(3)));
        assertThat(done.changed()).isEqualTo(3);
        assertThat(all()).hasSize(5);
        assertThat(variantService.overview(other.getId(), query("", "ALL", 100)).totalVariants()).isEqualTo(8);
    }

    @Test
    void productSkusAreMadeFromTheNameWhenLeftEmpty() {
        ProductForm form = new ProductForm();
        form.setName("Linen shirt - été");
        form.setPrice(new BigDecimal("30"));
        form.setSubcategoryId(subcategory.getId());
        Product first = productService.create(form);
        assertThat(first.getSku()).isEqualTo("LINEN-SHIRT-ETE");
        ProductForm second = new ProductForm();
        second.setName("Linen shirt - été");
        second.setPrice(new BigDecimal("30"));
        second.setSubcategoryId(subcategory.getId());
        assertThat(productService.create(second).getSku()).isEqualTo("LINEN-SHIRT-ETE-2");
    }

    @Test
    void variantSkusFollowTheProductSkuAndTheOptions() {
        assertThat(all().stream().map(ProductVariant::getSku)).contains("TEE-S-BLACK", "TEE-XL-SAND");
    }

    @Test
    void listingAVariantPageDoesNotQueryPerRow() {
        SessionFactory factory = emf.unwrap(SessionFactory.class);
        factory.getStatistics().setStatisticsEnabled(true);
        try {
            factory.getStatistics().clear();
            var page = variantService.overview(tee.getId(), query("", "ALL", 100)).page();
            long few = factory.getStatistics().getPrepareStatementCount();
            assertThat(page.getContent()).hasSize(8);
            assertThat(few).isLessThanOrEqualTo(7);
        } finally {
            factory.getStatistics().setStatisticsEnabled(false);
        }
    }

    @Test
    void thePageAndItsActionsWorkForManagersAndStayClosedToCustomers() throws Exception {
        var asManager = user(new AppUserDetails(manager));
        String base = "/manage/products/" + tee.getId() + "/variants";
        mvc.perform(get(base).param("q", "sand").param("size", "10").with(asManager)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Showing 1-4 of 4")))
                .andExpect(content().string(containsString("TEE-S-SAND")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("TEE-S-BLACK"))));

        Long first = idsOfFirst(1).get(0);
        mvc.perform(post(base + "/bulk").with(asManager).with(csrf()).param("action", "DEACTIVATE").param("ids", String.valueOf(first))
                        .param("q", "sand").param("size", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(base + "?q=sand&size=10"))
                .andExpect(flash().attribute("success", "1 variant updated"));

        mvc.perform(post(base + "/bulk").with(asManager).with(csrf()).param("action", "DEACTIVATE"))
                .andExpect(flash().attribute("error", "No variants selected"));
        mvc.perform(post(base + "/bulk").with(asManager).with(csrf()).param("action", "BOGUS"))
                .andExpect(flash().attribute("error", "Choose a valid action"));

        mvc.perform(post(base + "/values").with(asManager).with(csrf()).param("optionTypeId", String.valueOf(size.getId())).param("label", "XXL"))
                .andExpect(flash().attributeExists("success"));
        assertThat(idsOf("Size")).hasSize(5);
        mvc.perform(post(base + "/values").with(asManager).with(csrf()).param("optionTypeId", String.valueOf(size.getId())).param("label", "xxl"))
                .andExpect(flash().attributeExists("error"));

        var asCustomer = user(new AppUserDetails(users.create("Bea", "Buyer", "bea@example.com", TestPasswords.strong(), Role.CUSTOMER)));
        mvc.perform(get(base).with(asCustomer)).andExpect(status().isForbidden());
        mvc.perform(post(base + "/bulk").with(asCustomer).with(csrf()).param("action", "DELETE")).andExpect(status().isForbidden());
        mvc.perform(post(base + "/bulk").with(asManager).param("action", "DELETE")).andExpect(status().isForbidden());
    }

    @Test
    void aValueCanOnlyBeAddedToAnOptionTheProductsTypeOffers() throws Exception {
        OptionType material = optionService.createType(optionForm("Material", OptionType.Display.TEXT));
        var asManager = user(new AppUserDetails(manager));
        mvc.perform(post("/manage/products/" + tee.getId() + "/variants/values").with(asManager).with(csrf())
                        .param("optionTypeId", String.valueOf(material.getId())).param("label", "Wool"))
                .andExpect(flash().attribute("error", "This product's type does not offer that option"));
        assertThat(idsOf("Material")).isEmpty();
    }
    @Test
    void aProductCanDefineItsOwnLowStockLevel() {
        BulkVariantForm add = bulk(BulkVariantForm.Action.ADJUST_STOCK, idsOfFirst(1));
        add.setDelta(15);
        variantService.bulk(tee.getId(), new VariantQuery(), add);
        assertThat(variantService.overview(tee.getId(), query("", "LOW", 25)).page().getTotalElements()).isZero();

        ProductForm form = productService.formFor(tee.getId());
        form.setLowStockThreshold(20);
        productService.update(tee.getId(), form);
        assertThat(variantService.overview(tee.getId(), query("", "LOW", 25)).page().getTotalElements()).isEqualTo(1);
        assertThat(productService.get(tee.getId()).getLowStockLevel()).isEqualTo(20);
    }
    @Test
    void thePriceFilterLooksAtWhatEachVariantReallyCosts() {
        variantService.bulk(tee.getId(), new VariantQuery(), withPrice(bulk(BulkVariantForm.Action.SET_PRICE, idsOfFirst(2)), "99.00"));
        assertThat(shopIds("90", "")).contains(tee.getId());
        assertThat(shopIds("100", "")).doesNotContain(tee.getId());
        assertThat(shopIds("", "20")).doesNotContain(tee.getId());
        assertThat(shopIds("", "30")).contains(tee.getId());
    }

    @Test
    void aProductSkuCannotCollideWithOneOfItsVariants() {
        String taken = variantService.productWithVariants(tee.getId()).getVariants().get(0).getSku();
        ProductForm form = productService.formFor(tee.getId());
        form.setSku(taken);
        assertThatThrownBy(() -> productService.update(tee.getId(), form))
                .isInstanceOf(ConflictException.class).hasMessageContaining("already used");
    }

    private List<Long> shopIds(String min, String max) {
        ShopQuery shop = new ShopQuery();
        shop.setMinPrice(min);
        shop.setMaxPrice(max);
        return productService.shop(shop).map(Product::getId).getContent();
    }}
