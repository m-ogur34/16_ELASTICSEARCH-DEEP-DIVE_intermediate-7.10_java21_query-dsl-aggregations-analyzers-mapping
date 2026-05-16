# 16 — Elasticsearch Deep Dive

**Difficulty:** Intermediate (7/10) · **Java 21** · **Spring Boot 3.2.5**

Elasticsearch ile tam metin arama, Query DSL, aggregation ve index yönetimi.

---

## Temel Kavramlar

```
Elasticsearch         ↔    Relational DB
─────────────────────────────────────────
Index                 ↔    Table
Document              ↔    Row
Field                 ↔    Column
Shard                 ↔    Partition
Mapping               ↔    Schema
```

---

## Document Mapping

```java
@Document(indexName = "products")
@Setting(settingPath = "elasticsearch/settings.json")
public class Product {

    @Field(type = FieldType.Text, analyzer = "custom_turkish")
    private String name;          // analyzed → full-text arama

    @Field(type = FieldType.Keyword)
    private String category;      // exact match, aggregation, sort

    @Field(type = FieldType.Nested)
    private List<Review> reviews; // her review bağımsız aranabilir

    @Field(type = FieldType.Object)
    private Map<String, Object> specs; // flat — nested arama yok
}
```

### Text vs Keyword
```
text    → tokenize + analyze → full-text search (match, multi_match)
keyword → exact match → term, terms, aggregations, sort
```

---

## Custom Analyzer

```json
{
  "analysis": {
    "analyzer": {
      "custom_turkish": {
        "tokenizer": "standard",
        "filter": ["lowercase", "turkish_stop", "turkish_stemmer", "asciifolding"]
      }
    }
  }
}
```
`asciifolding` → ş→s, ö→o, ü→u dönüşümü (typo toleransı)
`stemmer` → "koşuyor" → "koş" (kök kelime)

---

## Query DSL

### Match (Full-text)
```java
q.match(m -> m.field("name").query("laptop").fuzziness("AUTO"))
// fuzziness: 1-2 harf farklılığı tolere et
```

### Multi-match
```java
q.multiMatch(mm -> mm
    .query("gaming laptop")
    .fields("name^3", "description", "tags")  // name 3x boost
    .type(TextQueryType.BestFields))
```

### Bool Query
```java
q.bool(b -> b
    .must(m -> m.match(/* scoring */))         // skorlamaya katkı
    .filter(f -> f.term(/* exact, no score */)) // cache'lenir, hızlı
    .should(s -> s.term(/* boost */))           // varsa puan artar
    .mustNot(mn -> mn.term(/* exclude */)))
```

### Range
```java
q.range(r -> r.field("price")
    .gte(JsonData.of(1000))
    .lte(JsonData.of(50000)))
```

### Nested Query
```java
q.nested(n -> n
    .path("reviews")
    .query(nq -> nq.range(r -> r.field("reviews.rating").gte(JsonData.of(4))))
    .scoreMode(ChildScoreMode.Avg))
```

---

## Aggregations

### Terms (Kategori dağılımı)
```json
{
  "aggs": {
    "categories": {
      "terms": { "field": "category", "size": 50 }
    }
  }
}
```

### Stats (Fiyat istatistikleri)
```json
{
  "aggs": {
    "price_stats": {
      "stats": { "field": "price" }
    }
  }
}
→ { count, min, max, avg, sum }
```

### Date Histogram
```json
{
  "aggs": {
    "orders_per_month": {
      "date_histogram": {
        "field": "createdAt",
        "calendar_interval": "month"
      }
    }
  }
}
```

### Nested Agg
```json
{
  "aggs": {
    "review_stats": {
      "nested": { "path": "reviews" },
      "aggs": {
        "avg_rating": { "avg": { "field": "reviews.rating" } }
      }
    }
  }
}
```

---

## Highlight

```java
NativeQuery.builder()
    .withQuery(q -> q.match(m -> m.field("name").query(text)))
    .withHighlightQuery(h -> h.withHighlightParameters(p -> p
        .withFields(Map.of("name", new HighlightField("name")))))
    .build();

// Result: hit.getHighlightFields() → {"name": ["<em>Laptop</em> Pro"]}
```

---

## Index Yönetimi

```bash
# Index oluştur
PUT /products
{
  "settings": { "number_of_shards": 3, "number_of_replicas": 1 },
  "mappings": { "properties": { "name": { "type": "text" } } }
}

# Mapping güncelle (yeni alan ekle)
PUT /products/_mapping
{ "properties": { "new_field": { "type": "keyword" } } }

# Reindex (mapping değiştirmek için)
POST /_reindex
{ "source": { "index": "products" }, "dest": { "index": "products_v2" } }

# Alias (zero-downtime switch)
POST /_aliases
{ "actions": [
    { "remove": { "index": "products",    "alias": "products_alias" } },
    { "add":    { "index": "products_v2", "alias": "products_alias" } }
]}
```

---

## Sharding ve Scaling

```
Primary Shard → okuma + yazma
Replica Shard → sadece okuma (primary shard'ın kopyası)

number_of_shards  → belirlendikten sonra DEĞİŞTİRİLEMEZ (reindex gerekir)
number_of_replicas → runtime'da değiştirilebilir

Shard sayısı belirleme: hedef index boyutu / 50GB (kural değil, kılavuz)
```

---

## REST Endpoints

| Method | URL | Açıklama |
|--------|-----|----------|
| POST | `/api/search/products` | Döküman index'le |
| POST | `/api/search/products/bulk` | Toplu index |
| GET | `/api/search/products/{id}` | ID ile getir |
| DELETE | `/api/search/products/{id}` | Sil |
| GET | `/api/search/products/search?q=laptop` | Full-text arama |
| GET | `/api/search/products/autocomplete?prefix=lap` | Prefix arama |
| GET | `/api/search/products/advanced?text=&category=&minPrice=&maxPrice=` | Bool query |
| GET | `/api/search/products/highlight?q=gaming` | Vurgulu sonuç |
| GET | `/api/search/products/review-rating?min=4` | Nested query |
| GET | `/api/search/products/stats/categories` | Aggregation |

---

## Mülakat Soruları

**Q: `text` vs `keyword` farkı?**
A: `text` tokenize edilir, analyzed, full-text arama için. `keyword` tokenize edilmez, exact match + sort + aggregation için.

**Q: `must` vs `filter` farkı?**
A: `must` relevance skoruna katkı sağlar. `filter` evet/hayır — skora katkısı yok ama cache'lenir, daha hızlı.

**Q: Nested vs Object farkı?**
A: `object` flattened — ayrı arama yapılamaz. `nested` bağımsız aranabilir. Örnek: `reviews.rating >= 4 AND reviews.userId = "user1"` nested olmadan yanlış sonuç döner.

**Q: Shard sayısını neden sonradan değiştiremezsiniz?**
A: Document routing `hash(id) % number_of_shards` formülüne dayanır. Shard sayısı değişirse document'lar yanlış shardda kalır — reindex zorunlu.

**Q: Fuzzy search nedir?**
A: Edit distance (Levenshtein) ile benzer kelimeleri eşleştirir. `fuzziness=AUTO`: 1-2 karakter farkı tolere eder.

**Q: Alias neden kullanılır?**
A: Zero-downtime reindex için. Uygulama alias'a yazar/okur. Yeni index hazır olunca alias switch edilir, uygulama değişmez.

---

## Çalıştırma

```bash
# Elasticsearch başlat
docker run -d -p 9200:9200 \
  -e "discovery.type=single-node" \
  -e "xpack.security.enabled=false" \
  elasticsearch:8.13.0

# Uygulama
mvn spring-boot:run

# Test
curl "http://localhost:8080/api/search/products/search?q=laptop"
```
