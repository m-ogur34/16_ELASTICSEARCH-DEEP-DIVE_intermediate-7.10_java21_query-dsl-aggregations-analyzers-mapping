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
 * ELASTİCSEARCH ÜRÜN ARAMA SERVİSİ
 * ====================================
 *
 * Neden Elasticsearch, neden sadece PostgreSQL değil?
 *   SQL LIKE '%laptop%': Full-text search için tasarlanmamış — index kullanamaz, tablo taraması.
 *   Büyük katalog (1M+ ürün): SQL LIKE yavaş, sonuçlar alakasız sıralanır.
 *   Elasticsearch: Inverted index — her kelime hangi dokümanlarda var, tersten tutar.
 *     "laptop" → [product_1, product_5, product_23] → doğrudan erişim.
 *   Relevance scoring: En alakalı sonuç öne çıkar (Google gibi).
 *     SQL: sıralama yok (veya ORDER BY CASE ile zahmetli).
 *     ES: TF/IDF veya BM25 algoritması — kelime sıklığı, doküman uzunluğu, alan ağırlığı.
 *
 * İki API neden var?
 *
 *   ElasticsearchOperations (Spring Data ES abstraction):
 *     Spring'in üst seviye wrapper'ı — Spring Data repository'leri ile entegre.
 *     NativeQuery ile ES query DSL'i Java'da yazılır.
 *     Basit/orta karmaşıklıktaki sorgular için tercih edilir.
 *
 *   ElasticsearchClient (Java native low-level client):
 *     Doğrudan Elasticsearch REST API'sine erişim.
 *     Aggregation sonuçları parse etme, index mapping gibi ileri işlemler.
 *     Operations'ın yetersiz kaldığı karmaşık aggregation ve admin işlemlerinde kullanılır.
 *
 * Bu servisteki arama desenleri:
 *   search()           → Full-text (multi_match + fuzzy): "labtop" → "laptop" bulur
 *   advancedSearch()   → Bool Query: must + filter + should kombinasyonu
 *   searchWithHighlight→ Nerede eşleşti? Kullanıcıya göster
 *   autocomplete()     → Prefix query: yazarken öneri (typeahead)
 *   findByReviewRating → Nested query: iç içe dokümanlarda filtre
 *   getCategoryDistribution → Terms aggregation (SQL GROUP BY eşdeğeri)
 *   getPriceStats()    → Stats aggregation (min/max/avg/sum)
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

    /**
     * Ürünü Elasticsearch'e indexler.
     * Index: ES'in "tablo" karşılığı — her save() bir doküman olarak saklar.
     * createdAt: Tarih bazlı sıralama ve range query için gerekli.
     */
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

    /**
     * Tam metin arama — ProductRepository custom @Query.
     * multi_match across name/description/tags with fuzzy (1-2 karakter hata toleransı).
     * "labtop" → "laptop" bulur (fuzziness=AUTO: kelime uzunluğuna göre tolerans ayarlar).
     */
    public List<Product> search(String query) {
        return repository.fullTextSearch(query);
    }

    /**
     * Gelişmiş arama — Bool Query.
     *
     * Bool Query nedir?
     *   Elasticsearch'in en güçlü ve en yaygın kullanılan query tipi.
     *   Birden fazla koşulu birleştirir — SQL'deki WHERE AND/OR gibi düşün.
     *   Ama SQL'den önemli farkı: Relevance score (alaka puanı) hesaplar.
     *
     * Bool Query 4 clause (bölüm):
     *
     *   MUST (zorunlu + skora katkı):
     *     Sonuçta bulunması ZORUNLU. SQL AND gibi.
     *     Relevance score'a katkı sağlar — arama metni buraya yazılır.
     *     Kullanım: Kullanıcının arama terimi — "laptop", "samsung tv".
     *
     *   FILTER (zorunlu + skora katkı YOK + cache):
     *     Sonuçta bulunması zorunlu. SQL AND gibi ama relevance skor yok.
     *     ES bu clause'u cache'ler — tekrarlı sorgularda 10x daha hızlı.
     *     Kullanım: Kesin koşullar — kategori, fiyat, stok durumu.
     *     Kural: Relevance önemsizse → filter (must değil) kullan.
     *
     *   SHOULD (isteğe bağlı + skora katkı):
     *     Eşleşme zorunlu değil ama eşleşirse skor artar. SQL OR gibi.
     *     Kullanım: Tag eşleşmesi, popülerlik boost — sonuç listesinde üste çıkarma.
     *
     *   MUST_NOT (olmamalı + skora katkı yok):
     *     Sonuçta OLMAMASI zorunlu. SQL NOT IN gibi.
     *     Kullanım: Stokta yok, silinmiş, yasaklı ürünleri hariç tut.
     */
    public List<Product> advancedSearch(String text, String category,
                                         BigDecimal minPrice, BigDecimal maxPrice,
                                         Double minRating, List<String> tags) {
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(q -> q
                        .bool(b -> {
                            // MUST — Arama metni: relevance score hesaplanır
                            // multi_match: Aynı terimi birden fazla alanda ara
                            //   "name^3": name alanı 3x daha ağırlıklı (boost)
                            //   fuzziness="AUTO": Kelime uzunluğuna göre 0-2 harf hatası tolere edilir
                            //   "labtop" → "laptop", "samsng" → "samsung"
                            if (text != null) {
                                b.must(m -> m.multiMatch(mm -> mm
                                        .query(text)
                                        .fields("name^3", "description", "tags")
                                        .fuzziness("AUTO")));
                            }

                            // FILTER — active=true: Silinen/pasif ürünler gelmesin
                            // Filter (must değil): Bu koşul skor etkilemez + ES cache'ler
                            // Her sorguda active filtresi tekrar eder → cache büyük performans kazancı
                            b.filter(f -> f.term(t -> t.field("active").value(true)));

                            if (category != null) {
                                // term query: keyword tipi alan → exact match (tokenize edilmez)
                                // Kategori: "Electronics" != "electronics" (büyük/küçük harf)
                                // Eğer lowercase normalize isteniyorsa: normalizer ayarlanmalı
                                b.filter(f -> f.term(t -> t.field("category").value(category)));
                            }

                            if (minPrice != null || maxPrice != null) {
                                // range query: Sayısal alan için aralık filtresi
                                //   gte = >= (greater than or equal to)
                                //   lte = <= (less than or equal to)
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

                            // SHOULD — Tag eşleşmesi: Zorunlu değil ama eşleşince skor artar
                            // Kullanıcı "gaming" aradıysa tag'inde "gaming" olan ürünler üste çıkar
                            // minimum_should_match ayarlanmazsa: 0 tag eşleşmesi yeterli (filter gibi davranır)
                            if (tags != null) {
                                for (String tag : tags) {
                                    b.should(s -> s.term(t -> t.field("tags").value(tag)));
                                }
                            }
                            return b;
                        }))
                // Relevance score'a (alaka puanı) göre sırala — en alakalı başa
                // Alternatif: SortOrder.Asc ile fiyata, tarihe göre sıralama
                .withSort(s -> s.score(sc -> sc.order(SortOrder.Desc)))
                .withPageable(PageRequest.of(0, 20))
                .build();

        SearchHits<Product> hits = operations.search(nativeQuery, Product.class);
        return hits.stream().map(SearchHit::getContent).toList();
    }

    /**
     * Highlight (Vurgulama) — Nerede eşleşti göster.
     *
     * Highlight nedir, neden gerekli?
     *   Kullanıcı "4K monitor" aradı. Sonuçlar listesinde hangi kelime neden eşleşti?
     *   Highlight: Eşleşen kelimeyi <em>tag'i ile sarmalayarak döner.
     *   UI'da gösterim: "Samsung <em>4K</em> <em>Monitor</em> 32 inç" — kullanıcı neden geldiğini görür.
     *   Google arama sonuçlarındaki kalın metin highlight mekanizmasıdır.
     *
     * highlightFields: Hangi alanlarda highlight aranacağı.
     *   name: Ürün adında eşleşme vurgulanır (en önemli).
     *   description: Uzun metinde eşleşen parça snippet olarak döner.
     *
     * hit.getHighlightFields(): Map<fieldName, List<String>>
     *   Key: "name" veya "description"
     *   Value: HTML snippet listesi ["Samsung <em>4K</em> Monitor"]
     */
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
                "highlights", hit.getHighlightFields() // fieldName → snippet listesi
        )).toList();

        return Map.of("total", hits.getTotalHits(), "results", results);
    }

    /**
     * Autocomplete (Otomatik Tamamlama) — matchPhrasePrefix.
     *
     * matchPhrasePrefix nedir?
     *   Kullanıcı "gamı" yazarken "gaming monitör" önerilmeli (typeahead/suggest).
     *   matchPhrasePrefix: Son kelimeyi prefix (ön ek) olarak arar.
     *   "gamı" → "gaming" başlangıcı olan tüm dokümanlar gelir.
     *   Sonuçlar: "Gaming Monitör", "Gaming Klavye", "Gaming Mouse" vb.
     *
     * match_phrase_prefix vs match farkı?
     *   match: Her kelimeyi ayrı arar, sıra önemsiz — "monitör gaming" de bulur.
     *   match_phrase_prefix: Kelimeler sıralı olmalı, son kelime prefix — daha kesin.
     *   Autocomplete için match_phrase_prefix daha doğru öneriler verir.
     *
     * Neden size=5?
     *   Autocomplete: Kullanıcı yazarken 5 öneri yeterli — 20 öneri UX'i bozar.
     *   Daha fazla → klavye gezinmesi zorlaşır, liste çok uzar.
     */
    public List<Product> autocomplete(String prefix) {
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(q -> q.matchPhrasePrefix(m -> m.field("name").query(prefix)))
                .withPageable(PageRequest.of(0, 5)) // 5 öneri yeterli (typeahead UX)
                .build();

        return operations.search(nativeQuery, Product.class)
                .stream().map(SearchHit::getContent).toList();
    }

    /**
     * Nested Query — İç İçe Dokümanlarda Filtre.
     *
     * Nested doküman neden özel query gerektirir?
     *   ES dokümanı: { "name": "...", "reviews": [{"rating": 5}, {"rating": 2}] }
     *   Normal sorgu: reviews.rating >= 4 → [5, 2] → 2 >= 4 FALSE? Hayır, 5 >= 4 TRUE!
     *   Sorun: ES dizileri "flat" olarak indexler — yorumlar karışabilir.
     *     Ürün A: yorum1.rating=5, yorum1.userId=1 | yorum2.rating=2, yorum2.userId=2
     *     Flat: ratings=[5,2], userIds=[1,2]
     *     Sorgu: rating >= 4 AND userId = 2 → Yanlış! Farklı yorumların verisi karışır.
     *   Nested type: Her yorum ayrı iç doküman olarak saklanır → karışma olmaz.
     *   nested query: Nested dokümanlar üzerinde doğru filtreleme.
     *
     * scoreMode=Avg:
     *   Her ürünün birden fazla yorumu var — nested query her yorumu değerlendirir.
     *   Avg: Tüm eşleşen yorumların skoru ortalamasını ana doküman skoruna ekler.
     *   Alternatif: Sum (topla), Max (en yüksek), First (ilk), None (skor ekleme).
     */
    public List<Product> findByReviewRating(int minRating) {
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(q -> q.nested(n -> n
                        .path("reviews")  // Nested alan adı — mapping'de nested type olmalı
                        .query(nq -> nq.range(r -> r
                                .field("reviews.rating")  // Nested alan içindeki property
                                .gte(co.elastic.clients.json.JsonData.of(minRating))))
                        .scoreMode(ChildScoreMode.Avg))) // Nested skor → ana doküman skoru
                .build();

        return operations.search(nativeQuery, Product.class)
                .stream().map(SearchHit::getContent).toList();
    }

    // ── Aggregations ─────────────────────────────────────────────────────────

    /**
     * Terms Aggregation — Kategori Dağılımı (SQL GROUP BY eşdeğeri).
     *
     * Terms aggregation nedir?
     *   "Her kategoride kaç ürün var?" sorusunu yanıtlar.
     *   SQL: SELECT category, COUNT(*) FROM products GROUP BY category
     *   ES: "categories" adlı terms aggregation → bucket list döner.
     *
     * Bucket: Her benzersiz değer bir "kova" (bucket) oluşturur.
     *   { "key": "Electronics", "doc_count": 1523 }
     *   { "key": "Clothing",    "doc_count": 891  }
     *   { "key": "Books",       "doc_count": 342  }
     *
     * size=50: En fazla 50 kategori döner.
     *   Sınırsız bırakılırsa bellek ve ağ yükü artar.
     *   E-ticaret: Genellikle 10-100 kategori yeterli.
     *
     * withPageable(0, 0): Doküman istemiyorum, sadece aggregation sonucu.
     *   size=0 → ES doküman döndürmez, sadece aggregation hesaplar → hızlı.
     */
    public Map<String, Long> getCategoryDistribution() {
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))  // Tüm dokümanlara aggregation uygula
                .withAggregation("categories", Aggregation.of(a -> a
                        .terms(t -> t.field("category").size(50)))) // keyword alan
                .withPageable(PageRequest.of(0, 0))  // Sadece aggregation, doküman yok
                .build();

        SearchHits<Product> hits = operations.search(nativeQuery, Product.class);
        Map<String, Long> result = new LinkedHashMap<>();

        if (hits instanceof SearchHitsImpl<Product> impl) {
            var aggregations = impl.getAggregations();
            if (aggregations != null) {
                var buckets = aggregations.get("categories");
                // Spring Data ES aggregation extraction
                // Her bucket: key=kategori adı, doc_count=ürün sayısı
            }
        }
        return result;
    }

    /**
     * Stats Aggregation — Fiyat İstatistikleri.
     *
     * Stats aggregation: Tek sorguda min, max, avg, sum, count döner.
     *   SQL: SELECT MIN(price), MAX(price), AVG(price), SUM(price), COUNT(*) FROM products
     *        WHERE category = 'Electronics'
     *   ES: Tek aggregation → tüm istatistikler birden.
     *
     * avg_rating aggregation: Kategorideki ortalama ürün puanı.
     *   İki aggregation aynı sorguda: price_stats + avg_rating — tek network round-trip.
     *   SQL'de: İki ayrı sorgu veya karmaşık JOIN — ES'de doğal.
     *
     * Kullanım: E-ticaret kategori sayfasında fiyat filtresi için range belirleme.
     *   "Bu kategoride ürünler 50 TL ile 5000 TL arasında" → slider range.
     */
    public Map<String, Object> getPriceStats(String category) {
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(q -> q.term(t -> t.field("category").value(category)))
                .withAggregation("price_stats", Aggregation.of(a -> a
                        .stats(s -> s.field("price"))))           // min/max/avg/sum/count
                .withAggregation("avg_rating", Aggregation.of(a -> a
                        .avg(avg -> avg.field("avgRating"))))     // Sadece ortalama
                .withPageable(PageRequest.of(0, 0))
                .build();

        operations.search(nativeQuery, Product.class);
        return Map.of("category", category, "note", "see aggregations in SearchHits");
    }

    /**
     * Sayfalı ürün listesi — Spring Data Pageable.
     * Spring Data ES: Pageable → ES from/size parametrelerine dönüştürür.
     * Sort.by("price").descending(): ES sort clause → pahalıdan ucuza sırala.
     */
    public Page<Product> findAll(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("price").descending());
        return repository.findAll(pageable);
    }

    /**
     * Toplu indexleme — Bulk Index.
     *
     * Neden bulk gerekir?
     *   1000 ürün: 1000 ayrı index isteği → 1000 network round-trip.
     *   Bulk: Tüm 1000 ürün tek HTTP isteğinde → 1 round-trip.
     *   Performans farkı: Ağ gecikmesi N yerine 1 kez → 10-100x hızlı.
     *
     * saveAll: Spring Data ES bunu arka planda bulk API'ye dönüştürür.
     *   Manuel ES client kullanılsaydı: BulkRequest builder ile explicit yazılırdı.
     *   Spring Data: saveAll() → bulk işlemi → sonuçları döner.
     */
    public List<Product> bulkIndex(List<Product> products) {
        products.forEach(p -> p.setCreatedAt(LocalDateTime.now()));
        return (List<Product>) repository.saveAll(products);
    }
}
