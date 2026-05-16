package com.elasticsearch.repository;

import com.elasticsearch.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.math.BigDecimal;
import java.util.List;

/**
 * Spring Data Elasticsearch Repository
 * — Method naming convention (JPA ile aynı mantık)
 * — @Query ile native ES JSON query
 */
public interface ProductRepository extends ElasticsearchRepository<Product, String> {

    // Method naming
    List<Product> findByCategory(String category);
    List<Product> findByCategoryAndActiveTrue(String category);
    Page<Product> findByCategory(String category, Pageable pageable);
    List<Product> findByPriceBetween(BigDecimal min, BigDecimal max);
    List<Product> findByTagsContaining(String tag);
    List<Product> findByAvgRatingGreaterThanEqual(double minRating);

    // Native ES query
    @Query("""
            {
              "bool": {
                "must": [
                  { "match": { "name": "?0" } }
                ],
                "filter": [
                  { "term": { "active": true } },
                  { "range": { "price": { "lte": ?1 } } }
                ]
              }
            }
            """)
    List<Product> findAffordableByName(String name, BigDecimal maxPrice);

    // Multi-match query
    @Query("""
            {
              "multi_match": {
                "query": "?0",
                "fields": ["name^3", "description", "tags"],
                "type": "best_fields",
                "fuzziness": "AUTO"
              }
            }
            """)
    List<Product> fullTextSearch(String query);

    long countByCategoryAndActiveTrue(String category);

    boolean existsByNameAndCategory(String name, String category);
}
