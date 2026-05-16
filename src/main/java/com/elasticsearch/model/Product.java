package com.elasticsearch.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch Document Mapping:
 *   @Document     → index adı ve settings
 *   @Field        → alan tipi ve analyzer
 *   text          → tam metin arama (analyzed)
 *   keyword       → exact match, aggregation, sort
 *   nested        → iç içe obje arama
 */
@Document(indexName = "products", createIndex = true)
@Setting(settingPath = "elasticsearch/settings.json")
public class Product {

    @Id
    private String id;

    @Field(type = FieldType.Text, analyzer = "custom_turkish")
    private String name;

    @Field(type = FieldType.Text, analyzer = "custom_turkish",
           searchAnalyzer = "standard")
    private String description;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Keyword)
    private List<String> tags;

    @Field(type = FieldType.Double)
    private BigDecimal price;

    @Field(type = FieldType.Integer)
    private int stock;

    @Field(type = FieldType.Boolean)
    private boolean active;

    @Field(type = FieldType.Double)
    private double avgRating;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime createdAt;

    // Nested mapping — her review ayrı aranabilir
    @Field(type = FieldType.Nested)
    private List<Review> reviews;

    // Object mapping — flat, nested arama yok
    @Field(type = FieldType.Object)
    private Map<String, Object> specifications;

    public record Review(
            @Field(type = FieldType.Keyword) String userId,
            @Field(type = FieldType.Integer) int rating,
            @Field(type = FieldType.Text, analyzer = "standard") String comment
    ) {}

    public Product() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public double getAvgRating() { return avgRating; }
    public void setAvgRating(double avgRating) { this.avgRating = avgRating; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public List<Review> getReviews() { return reviews; }
    public void setReviews(List<Review> reviews) { this.reviews = reviews; }
    public Map<String, Object> getSpecifications() { return specifications; }
    public void setSpecifications(Map<String, Object> specifications) { this.specifications = specifications; }
}
