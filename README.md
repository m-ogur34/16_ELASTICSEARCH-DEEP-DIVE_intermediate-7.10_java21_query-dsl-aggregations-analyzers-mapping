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

**Q: `text` vs `keyword` alan tipi farkı nedir?**
A: `text`: Analyzer ile tokenize edilir — "Spring Boot" → ["spring", "boot"]. Full-text search için: `match`, `multi_match`, `query_string`. Sıralama ve aggregation yapılamaz (tokenize edildiği için). `keyword`: Tokenize edilmez, tam değer saklanır. Exact match (`term`), sıralama (`sort`), aggregation (`terms agg`) için. Örnek: `status`, `category`, `email` → keyword; `description`, `title` → text. Multi-field mapping: Aynı alana her iki tip — `name.keyword` sort için, `name` search için. Yanlış tip → sorgu çalışmaz (text alanına term query sonuç vermez).

**Q: `must`, `should`, `filter`, `must_not` farkları nelerdir?**
A: `must`: Koşul sağlanmalı + relevance skoruna katkı sağlar — "en alakalı sonuç" için. `filter`: Koşul sağlanmalı ama skora katkı yok — cache'lenir, daha hızlı. Filtreleme için (tarih aralığı, status). `should`: En az biri sağlanmalı (OR) — skor artar. `minimum_should_match` ile kaç tane zorunlu belirlenebilir. `must_not`: Koşul sağlanmamalı, skora katkı yok. Performans: Filterleri `filter` context'e koy — ES bitset cache'ler. Örnek: kategori filtresi → `filter`, arama metni → `must`.

**Q: Nested vs Object farkı? Ne zaman nested kullanılır?**
A: `object` (default): Elasticsearch array'deki nesneleri flatten'lar — `[{userId:"u1", rating:5}, {userId:"u2", rating:2}]` → `userId: ["u1","u2"], rating: [5,2]`. `reviews.rating >= 4 AND reviews.userId = "u1"` sorgusu yanlış sonuç döner (çapraz eşleşme). `nested`: Her dizi elemanı bağımsız belge olarak indexlenir, aralarındaki ilişki korunur. Doğru sorgu: `nested query` ile `reviews.rating >= 4 AND reviews.userId = "u1"` → yalnızca aynı review'da her iki koşul sağlananlar. Maliyet: Her `nested` update → tüm nested document'lar yeniden index'lenir. Yüksek güncelleme sıklığında dikkat.

**Q: Shard ve Replica nedir? Shard sayısı neden değiştirilemez?**
A: Shard: Index'in bölümleri — her shard ayrı Lucene index. Yatay ölçekleme sağlar (10M doc → 5 shard × 2M doc). Replica: Shard kopyası — okuma throughput + availability. Primary shard düşerse replica promote edilir. Shard sayısı değiştirilemez: Document routing = `hash(id) % number_of_shards`. Değiştirilirse mevcut document'lar yanlış shardda kalır, bulunamaz. Çözüm: Yeni index oluştur (doğru shard sayısıyla) → `_reindex` API ile veriyi taşı → alias switch. Kural: Baştan doğru tahmin et; hot-warm architecture için daha az, büyük cluster için 3-5 shard/index.

**Q: Fuzzy search ve full-text search nasıl çalışır?**
A: Full-text search: Analyzer pipeline — `char_filter` (HTML strip) → `tokenizer` (kelimeye böl) → `token_filter` (lowercase, stop words, stemming). "Running shoes" → ["run", "shoe"]. `match` query aynı pipeline'dan geçirir → token bazlı arama. Fuzzy search: `fuzziness: "AUTO"` — kısa kelimelerde (1-2 char) 0 edit distance, uzun kelimelerde 1-2 Levenshtein edit toleransı. "labtop" → "laptop" (1 char farkı). Performans: Fuzzy pahalı, prefix/wildcard sorgular çok pahalı (leading wildcard = full scan). Önerilen: `edge_ngram` analyzer ile prefix search, `search_as_you_type` tipi ile autocomplete.

**Q: Alias neden kullanılır? Zero-downtime reindex nasıl yapılır?**
A: Problem: Mapping değişikliği (yeni alan tipi) reindex gerektirir — yüzlerce GB'lık index'i yeniden oluştururken uygulama çalışmalı. Alias çözümü: (1) Uygulama `products` alias'ına yazar/okur. (2) `products` → `products_v1`'i gösterir. (3) `products_v2` oluştur, yeni mapping ile. (4) `_reindex` API: `products_v1` → `products_v2` (arka planda). (5) Alias switch: `products` → `products_v2`. (6) `products_v1`'i sil. Uygulama hiç değişmedi, downtime yok. Index Template + ILM (Index Lifecycle Management): Log index'leri otomatik rotate et, yaşlı index'leri hot → warm → cold → delete.

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
