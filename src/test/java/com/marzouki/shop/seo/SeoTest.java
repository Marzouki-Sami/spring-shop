package com.marzouki.shop.seo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marzouki.shop.catalog.CategoryRepository;
import com.marzouki.shop.catalog.CategoryService;
import com.marzouki.shop.catalog.Product;
import com.marzouki.shop.catalog.ProductRepository;
import com.marzouki.shop.catalog.ProductService;
import com.marzouki.shop.catalog.SubcategoryRepository;
import com.marzouki.shop.catalog.SubcategoryService;
import com.marzouki.shop.catalog.dto.CategoryForm;
import com.marzouki.shop.catalog.dto.ProductForm;
import com.marzouki.shop.catalog.dto.SubcategoryForm;
import com.marzouki.shop.order.StockMovementRepository;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.site.url=https://shop.example.com/")
class SeoTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProductService productService;
    @Autowired CategoryService categoryService;
    @Autowired SubcategoryService subcategoryService;
    @Autowired ProductRepository productRepository;
    @Autowired SubcategoryRepository subcategories;
    @Autowired CategoryRepository categories;
    @Autowired StockMovementRepository movements;

    Product lamp;
    Product hidden;

    @BeforeEach
    void data() {
        CategoryForm category = new CategoryForm();
        category.setTitle("Home");
        SubcategoryForm sub = new SubcategoryForm();
        sub.setTitle("Lamps");
        sub.setCategoryId(categoryService.create(category).getId());
        Long subId = subcategoryService.create(sub).getId();
        lamp = product("LAMP", "Desk lamp", "A bright lamp </script><script>alert(1)</script> & more", "24.5", 3, subId, true);
        hidden = product("HID", "Secret", "Not for sale", "5", 1, subId, false);
    }

    @AfterEach
    void cleanUp() {
        movements.deleteAll();
        productRepository.deleteAll();
        subcategories.deleteAll();
        categories.deleteAll();
    }

    private Product product(String sku, String name, String description, String price, int stock, Long subId, boolean active) {
        ProductForm form = new ProductForm();
        form.setSku(sku);
        form.setName(name);
        form.setDescription(description);
        form.setPrice(new BigDecimal(price));
        form.setStock(stock);
        form.setSubcategoryId(subId);
        form.setActive(active);
        return productService.create(form);
    }

    private String page(String path, String... params) throws Exception {
        var request = get(path);
        for (int i = 0; i + 1 < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void robotsTxtKeepsPrivateAreasOutAndPointsToTheSitemap() throws Exception {
        mvc.perform(get("/robots.txt")).andExpect(status().isOk()).andExpect(header().string("Content-Type", containsString("text/plain")))
                .andExpect(content().string(containsString("Disallow: /manage/")))
                .andExpect(content().string(containsString("Disallow: /admin/")))
                .andExpect(content().string(containsString("Disallow: /account/")))
                .andExpect(content().string(containsString("Disallow: /cart")))
                .andExpect(content().string(containsString("Sitemap: https://shop.example.com/sitemap.xml")));
    }

    @Test
    void theSitemapListsActiveProductsOnly() throws Exception {
        String xml = page("/sitemap.xml");
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(document.getElementsByTagName("loc").getLength()).isGreaterThanOrEqualTo(3);
        assertThat(xml).contains("<loc>https://shop.example.com/</loc>").contains("<loc>https://shop.example.com/products</loc>")
                .contains("<loc>https://shop.example.com/products/" + lamp.getId() + "</loc>")
                .doesNotContain("/products/" + hidden.getId() + "<").contains("<lastmod>");
    }

    @Test
    void aProductPageCarriesSearchAndSharingInformation() throws Exception {
        String html = page("/products/" + lamp.getId());
        String canonical = "https://shop.example.com/products/" + lamp.getId();
        assertThat(html).contains("<link rel=\"canonical\" href=\"" + canonical + "\"")
                .contains("property=\"og:type\" content=\"product\"")
                .contains("property=\"og:title\" content=\"Desk lamp\"")
                .contains("property=\"og:url\" content=\"" + canonical + "\"")
                .contains("name=\"description\"").doesNotContain("noindex");

        Matcher matcher = Pattern.compile("<script type=\"application/ld\\+json\">(.*?)</script>", Pattern.DOTALL).matcher(html);
        assertThat(matcher.find()).as("structured data present").isTrue();
        JsonNode data = json.readTree(matcher.group(1));
        assertThat(data.get("@type").asText()).isEqualTo("Product");
        assertThat(data.get("name").asText()).isEqualTo("Desk lamp");
        assertThat(data.get("sku").asText()).isEqualTo("LAMP");
        assertThat(data.get("offers").get("price").asText()).isEqualTo("24.50");
        assertThat(data.get("offers").get("priceCurrency").asText()).isEqualTo("EUR");
        assertThat(data.get("offers").get("availability").asText()).isEqualTo("https://schema.org/InStock");
        assertThat(data.get("description").asText()).contains("</script><script>alert(1)</script>");
        assertThat(html).doesNotContain("</script><script>alert(1)");
    }

    @Test
    void filteredAndPagedListsStayOutOfSearchResultsButTheMainOneIsIndexed() throws Exception {
        String plain = page("/products");
        assertThat(plain).contains("<link rel=\"canonical\" href=\"https://shop.example.com/products\"").doesNotContain("noindex");
        for (String[] variation : new String[][] {{"q", "lamp"}, {"sort", "price-asc"}, {"inStock", "true"}, {"page", "1"}, {"minPrice", "3"}}) {
            assertThat(page("/products", variation)).as(variation[0]).contains("content=\"noindex,follow\"")
                    .contains("<link rel=\"canonical\" href=\"https://shop.example.com/products\"");
        }
    }

    @Test
    void theHomePageIsDescribedToo() throws Exception {
        assertThat(page("/")).contains("<link rel=\"canonical\" href=\"https://shop.example.com/\"").contains("property=\"og:type\" content=\"website\"");
    }

    @Test
    void structuredDataCannotCloseItsScriptTag() {
        assertThat(com.marzouki.shop.common.ScriptJson.escape("{\"a\":\"</script><b>&\"}")).doesNotContain("<").doesNotContain(">").doesNotContain("&")
                .contains("\\u003c/script\\u003e");
    }

    @Test
    void hiddenProductsAreNotFoundAndNotDescribed() throws Exception {
        mvc.perform(get("/products/" + hidden.getId())).andExpect(status().isNotFound()).andExpect(content().string(not(containsString("og:type"))));
    }
}
