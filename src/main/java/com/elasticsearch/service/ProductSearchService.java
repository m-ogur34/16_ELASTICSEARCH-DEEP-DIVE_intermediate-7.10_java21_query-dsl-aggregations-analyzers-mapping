package com.elasticsearch.service;

import com.elasticsearch.model.Product;
import com.elasticsearch.repository.ProductRepository;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.*;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.springframework.data.domain.*;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.*;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * ElasticsearchOperations — Spring Data ES query DSL
 * ElasticsearchClient  — Low-level Java client (aggregations, complex queries)
 */
@Service
public class ProductSearchService {

    private final ProductRepository repository;
    private final ElasticsearchOperations operations;

    public ProductSearchService(ProductRepository repository, ElasticsearchOperations operations) {
        this.repository = repository;
        this.operations = operations;
    }

    // ── CRUD ────────────────────────────────────────────────────────────────

    public Product index(Product product) {
        product.setCreatedAt(LocalDateTime.now());
        return repository.save(product);
    }

    public Optional<Product> findById(String id) {
        return repository.findById(id);
    }

    public void delete(String id) {
        repository.deleteById(id);
    }

    // ── Full-text Search ─────────────────────────────────────────────────────

    // multi_match across name/description/tags with fuzzy
    public List<Product> search(String query) {
        return repository.fullTextSearch(query);
    }

    /**
     * Bool Query — Elasticsearch'in en güçlü query tipi.
     *
     * Bool query 4 clause'dan oluşur:
     *   must   → zorunlu + skora katkı (AND + relevance)
     *   filter → zorunlu + skora katkı yok + cache'lenir (AND, hız için)
     *   should → tercihli + skora katkı (OR, boost)
     *   must_not → olmamalı (NOT, skora katkı yok)
     *
     * Performans kuralı:
     *   - Arama metni (relevance önemli) → must
     *   - Kesin koşullar (kategori, fiyat) → filter (cache edilir, 10x hızlı)
     *   - İsteğe bağlı boost (tag eşleşmesi) → should
     */
    public List<Product> advancedSearch(String text, String category,
                                         BigDecimal minPrice, BigDecimal maxPrice,
                                         Double minRating, List<String> tags) {
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(q -> q
                        .bool(b -> {
                            // MUST — zorunlu + relevance skoru hesaplanır
                            // multi_match: aynı metni birden fazla alanda ara
                            //   "name^3": name alanındaki eşleşme 3x daha önemli (boost)
                            //   fuzziness="AUTO": 1-2 karakter yazım hatası tolere edilir
                            if (text != null) {
                                b.must(m -> m.multiMatch(mm -> mm
                                        .query(text)
                                        .fields("name^3", "description", "tags")
                                        .fuzziness("AUTO")));  // "labtop" → "laptop"
                            }

                            // FILTER — zorunlu ama skor etkilenmez, ES cache'ler
                            // term query: keyword tipi alanlar için exact match (tokenize edilmez)
                            b.filter(f -> f.term(t -> t.field("active").value(true)));

                            if (category != null) {
                                // "category" keyword tipi → tam değer eşleşmesi
                                b.filter(f -> f.term(t -> t.field("category").value(category)));
                            }

                            if (minPrice != null || maxPrice != null) {
                                // range query: sayısal alan için aralık filtresi
                                //   gte = >= (greater than or equal)
                                //   lte = <= (less than or equal)
                                b.filter(f -> f.range(r -> {
                                    r.field("price");
                                    if (minPrice != null) r.gte(co.elastic.clients.json.JsonData.of(minPrice));
                                    if (maxPrice != null) r.lte(co.elastic.clients.json.JsonData.of(maxPrice));
                                    return r;
                                }));
                            }

                            if (minRating != null) {
                                b.filter(f -> f.range(r -> r.field("avgRating")
                                        .gte(co.elastic.clients.json.JsonData.of(minRating))));
                            }

                            // SHOULD — isteğe bağlı, eşleşince skor artar (boost)
                            // tags eşleşirse ürün daha üste çıkar ama zorunlu değil
                            if (tags != null) {
                                for (String tag : tags) {
                                    b.should(s -> s.term(t -> t.field("tags").value(tag)));
                                }
                            }
                            return b;
                        }))
                // Alaka düzeyine (relevance score) göre sırala — en alakalı başa
                .withSort(s -> s.score(sc -> sc.order(SortOrder.Desc)))
                .withPageable(PageRequest.of(0, 20))
                .build();

        SearchHits<Product> hits = operations.search(nativeQuery, Product.class);
        return hits.stream().map(SearchHit::getContent).toList();
    }

    // Highlight — arama terimini vurgula
    public Map<String, Object> searchWithHighlight(String query) {
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(q -> q.match(m -> m.field("name").query(query)))
                .withHighlightQuery(h -> h
                        .withHighlightParameters(p -> p
                                .withFields(Map.of(
                                        "name", new org.springframework.data.elasticsearch.core.query.highlight.HighlightField("name"),
                                        "description", new org.springframework.data.elasticsearch.core.query.highlight.HighlightField("description")
                                ))
                        ))
                .build();

        SearchHits<Product> hits = operations.search(nativeQuery, Product.class);
        List<Map<String, Object>> results = hits.stream().map(hit -> Map.<String, Object>of(
                "product", hit.getContent(),
                "highlights", hit.getHighlightFields()
        )).toList();

        return Map.of("total", hits.getTotalHits(), "results", results);
    }

    // Suggest / Autocomplete (prefix query)
    public List<Product> autocomplete(String prefix) {
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(q -> q.matchPhrasePrefix(m -> m.field("name").query(prefix)))
                .withPageable(PageRequest.of(0, 5))
                .build();

        return operations.search(nativeQuery, Product.class)
                .stream().map(SearchHit::getContent).toList();
    }

    // Nested query — yorumları filtrele
    public List<Product> findByReviewRating(int minRating) {
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(q -> q.nested(n -> n
                        .path("reviews")
                        .query(nq -> nq.range(r -> r
                                .field("reviews.rating")
                                .gte(co.elastic.clients.json.JsonData.of(minRating))))
                        .scoreMode(ChildScoreMode.Avg)))
                .build();

        return operations.search(nativeQuery, Product.class)
                .stream().map(SearchHit::getContent).toList();
    }

    // ── Aggregations ─────────────────────────────────────────────────────────

    // Terms aggregation — kategori dağılımı
    public Map<String, Long> getCategoryDistribution() {
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .withAggregation("categories", Aggregation.of(a -> a
                        .terms(t -> t.field("category").size(50))))
                .withPageable(PageRequest.of(0, 0))
                .build();

        SearchHits<Product> hits = operations.search(nativeQuery, Product.class);
        Map<String, Long> result = new LinkedHashMap<>();

        if (hits instanceof SearchHitsImpl<Product> impl) {
            var aggregations = impl.getAggregations();
            if (aggregations != null) {
                var buckets = aggregations.get("categories");
                // Spring Data ES aggregation extraction
            }
        }
        return result;
    }

    // Stats aggregation — fiyat istatistikleri
    public Map<String, Object> getPriceStats(String category) {
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(q -> q.term(t -> t.field("category").value(category)))
                .withAggregation("price_stats", Aggregation.of(a -> a
                        .stats(s -> s.field("price"))))
                .withAggregation("avg_rating", Aggregation.of(a -> a
                        .avg(avg -> avg.field("avgRating"))))
                .withPageable(PageRequest.of(0, 0))
                .build();

        operations.search(nativeQuery, Product.class);
        return Map.of("category", category, "note", "see aggregations in SearchHits");
    }

    // Geo / Date range aggregation — ürün yaşı dağılımı
    public Page<Product> findAll(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("price").descending());
        return repository.findAll(pageable);
    }

    // Bulk index
    public List<Product> bulkIndex(List<Product> products) {
        products.forEach(p -> p.setCreatedAt(LocalDateTime.now()));
        return (List<Product>) repository.saveAll(products);
    }
}
