package com.ai.smartinventory.ai;

import com.ai.smartinventory.entity.Product;
import com.ai.smartinventory.entity.UserBehavior;
import com.ai.smartinventory.mapper.ProductMapper;
import com.ai.smartinventory.mapper.UserBehaviorMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import jakarta.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CollaborativeFilteringRecommender {
    
    @Resource
    private UserBehaviorMapper userBehaviorMapper;
    
    @Resource
    private ProductMapper productMapper;
    
    private static final double SIMILARITY_THRESHOLD = 0.1;
    private static final double PEARSON_THRESHOLD = 0.3;
    private static final int DEFAULT_RECOMMEND_SIZE = 10;
    private static final double JACCARD_WEIGHT = 0.4;
    private static final double PEARSON_WEIGHT = 0.6;

    /**
     * 混合协同过滤推荐
     * 结合Jaccard相似度(行为偏好)和Pearson相关系数(评分模式)进行推荐
     */
    public List<Product> recommend(Long userId, int limit) {
        if (userId == null) {
            return getDefaultRecommendations(limit);
        }

        List<UserBehavior> userBehaviors = userBehaviorMapper.selectByUserId(userId);
        if (userBehaviors.isEmpty()) {
            return getDefaultRecommendations(limit);
        }

        Set<Long> userItemSet = userBehaviors.stream()
                .map(UserBehavior::getProductId)
                .collect(Collectors.toSet());

        // 构建用户评分向量
        Map<Long, Double> userRatingVector = buildRatingVector(userBehaviors);

        Map<Long, Double> itemScores = new HashMap<>();

        List<UserBehavior> allBehaviors = userBehaviorMapper.selectRecentBehaviors(30);

        Map<Long, List<UserBehavior>> userBehaviorMap = allBehaviors.stream()
                .collect(Collectors.groupingBy(UserBehavior::getUserId));

        for (Map.Entry<Long, List<UserBehavior>> entry : userBehaviorMap.entrySet()) {
            if (entry.getKey().equals(userId)) continue;

            Set<Long> otherItemSet = entry.getValue().stream()
                    .map(UserBehavior::getProductId)
                    .collect(Collectors.toSet());

            Map<Long, Double> otherRatingVector = buildRatingVector(entry.getValue());

            // 计算Jaccard相似度(行为偏好)
            double jaccardSim = calculateJaccardSimilarity(userItemSet, otherItemSet);

            // 计算Pearson相关系数(评分模式)
            double pearsonCorr = calculatePearsonCorrelation(userRatingVector, otherRatingVector);

            // 混合相似度
            double hybridSimilarity = JACCARD_WEIGHT * jaccardSim + PEARSON_WEIGHT * pearsonCorr;

            if (hybridSimilarity > SIMILARITY_THRESHOLD) {
                for (UserBehavior behavior : entry.getValue()) {
                    if (!userItemSet.contains(behavior.getProductId())) {
                        double score = calculateScore(behavior, hybridSimilarity);
                        itemScores.merge(behavior.getProductId(), score, Double::sum);
                    }
                }
            }
        }

        List<Long> topItemIds = itemScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (topItemIds.isEmpty()) {
            return getDefaultRecommendations(limit);
        }

        return productMapper.selectBatchIds(topItemIds);
    }

    /**
     * 构建用户评分向量
     * 将行为类型映射为隐式评分(1-5)
     */
    private Map<Long, Double> buildRatingVector(List<UserBehavior> behaviors) {
        Map<Long, Double> vector = new HashMap<>();
        for (UserBehavior behavior : behaviors) {
            double implicitRating = behavior.getRating() != null
                    ? behavior.getRating().doubleValue()
                    : getBehaviorWeight(behavior.getBehaviorType()) * 5.0;
            vector.put(behavior.getProductId(), implicitRating);
        }
        return vector;
    }

    /**
     * 计算Jaccard相似度 - 基于共同行为
     */
    private double calculateJaccardSimilarity(Set<Long> set1, Set<Long> set2) {
        if (set1.isEmpty() && set2.isEmpty()) return 0;

        Set<Long> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        Set<Long> union = new HashSet<>(set1);
        union.addAll(set2);

        return (double) intersection.size() / union.size();
    }

    /**
     * 计算Pearson相关系数 - 基于评分模式
     * 衡量两个用户评分趋势的一致性（-1到1）
     */
    private double calculatePearsonCorrelation(Map<Long, Double> vector1, Map<Long, Double> vector2) {
        // 找出共同评分的商品
        Set<Long> commonItems = new HashSet<>(vector1.keySet());
        commonItems.retainAll(vector2.keySet());

        if (commonItems.size() < 2) {
            return 0.0;
        }

        // 计算均值
        double avg1 = commonItems.stream().mapToDouble(vector1::get).average().orElse(0);
        double avg2 = commonItems.stream().mapToDouble(vector2::get).average().orElse(0);

        // 计算Pearson相关系数
        double sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (Long itemId : commonItems) {
            double x = vector1.get(itemId) - avg1;
            double y = vector2.get(itemId) - avg2;
            sumXY += x * y;
            sumX2 += x * x;
            sumY2 += y * y;
        }

        if (sumX2 == 0 || sumY2 == 0) {
            return 0.0;
        }

        return sumXY / (Math.sqrt(sumX2) * Math.sqrt(sumY2));
    }

    /**
     * 预测用户对未评分商品的评分
     * 基于相似用户的加权平均评分
     */
    public double predictRating(Long userId, Long productId, Map<Long, Double> similarities,
                                 Map<Long, Map<Long, Double>> userRatingVectors) {
        double weightedSum = 0;
        double similaritySum = 0;

        for (Map.Entry<Long, Double> simEntry : similarities.entrySet()) {
            Long otherUserId = simEntry.getKey();
            double similarity = simEntry.getValue();
            Map<Long, Double> otherRatings = userRatingVectors.get(otherUserId);

            if (otherRatings != null && otherRatings.containsKey(productId)) {
                weightedSum += similarity * otherRatings.get(productId);
                similaritySum += Math.abs(similarity);
            }
        }

        return similaritySum > 0 ? weightedSum / similaritySum : 0;
    }

    private double calculateScore(UserBehavior behavior, double similarity) {
        double behaviorWeight = getBehaviorWeight(behavior.getBehaviorType());
        Integer rating = behavior.getRating();
        double ratingScore = rating != null ? rating / 5.0 : 0.8;

        return similarity * behaviorWeight * ratingScore;
    }

    private double getBehaviorWeight(String behaviorType) {
        return switch (behaviorType) {
            case "purchase" -> 1.0;
            case "cart" -> 0.7;
            case "collect" -> 0.5;
            case "view" -> 0.3;
            default -> 0.1;
        };
    }
    
    private List<Product> getDefaultRecommendations(int limit) {
        List<Product> products = productMapper.selectRecommendProducts(limit);
        if (products.isEmpty()) {
            products = productMapper.selectHotProducts(limit);
        }
        return products;
    }
    
    @Data
    public static class SimilarUser {
        private Long userId;
        private double similarity;
    }
}
