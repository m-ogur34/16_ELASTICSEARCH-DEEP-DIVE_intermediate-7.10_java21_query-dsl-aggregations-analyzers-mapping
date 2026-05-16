package com.elasticsearch.controller;

import com.elasticsearch.model.Product;
import com.elasticsearch.service.ProductSearchService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final ProductSearchService service;

    public SearchController(ProductSearchService service) {
        this.service = service;
    }

    @PostMapping("/products")
    public ResponseEntity<Product> index(@RequestBody Product product) {
        return ResponseEntity.ok(service.index(product));
    }

    @PostMapping("/products/bulk")
    public ResponseEntity<List<Product>> bulkIndex(@RequestBody List<Product> products) {
        return ResponseEntity.ok(service.bulkIndex(products));
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<Product> findById(@PathVariable String id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/products/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/products")
    public ResponseEntity<Page<Product>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(service.findAll(page, size));
    }

    // Full-text search
    @GetMapping("/products/search")
    public ResponseEntity<List<Product>> search(@RequestParam String q) {
        return ResponseEntity.ok(service.search(q));
    }

    // Autocomplete
    @GetMapping("/products/autocomplete")
    public ResponseEntity<List<Product>> autocomplete(@RequestParam String prefix) {
        return ResponseEntity.ok(service.autocomplete(prefix));
    }

    // Advanced bool query
    @GetMapping("/products/advanced")
    public ResponseEntity<List<Product>> advancedSearch(
            @RequestParam(required = false) String text,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Double minRating,
            @RequestParam(required = false) List<String> tags) {
        return ResponseEntity.ok(service.advancedSearch(text, category, minPrice, maxPrice, minRating, tags));
    }

    // Highlight
    @GetMapping("/products/highlight")
    public ResponseEntity<Map<String, Object>> searchWithHighlight(@RequestParam String q) {
        return ResponseEntity.ok(service.searchWithHighlight(q));
    }

    // Nested query
    @GetMapping("/products/review-rating")
    public ResponseEntity<List<Product>> byReviewRating(@RequestParam(defaultValue = "4") int min) {
        return ResponseEntity.ok(service.findByReviewRating(min));
    }

    // Aggregations
    @GetMapping("/products/stats/categories")
    public ResponseEntity<Map<String, Long>> categoryDistribution() {
        return ResponseEntity.ok(service.getCategoryDistribution());
    }

    @GetMapping("/products/stats/price")
    public ResponseEntity<Map<String, Object>> priceStats(@RequestParam String category) {
        return ResponseEntity.ok(service.getPriceStats(category));
    }
}
