package com.movies.recommendation.service;

import com.movies.recommendation.dto.RecommendedMovie;
import java.util.List;

public interface IRecommendationService {

    List<RecommendedMovie> getRecommendations(String userId, Integer limit);
}
