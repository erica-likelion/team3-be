package likelion.service;

import likelion.domain.entity.Review;
import likelion.jsondata.mapper.ReviewMapper;
import likelion.jsondata.record.ReviewJson;
import likelion.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewMapper reviewMapper;
    private final ReviewRepository reviewRepository;

    @Transactional
    public void ingest(List<ReviewJson> rows) {
        for (ReviewJson j : rows) {
            Review entity = reviewMapper.map(j);
            Long placeId = entity.getRestaurant().getKakaoPlaceId();
            String sourceId = entity.getSourceReviewId();

            boolean exists = reviewRepository
                    .findByRestaurant_KakaoPlaceIdAndSourceReviewId(placeId, sourceId)
                    .isPresent();

            if (!exists) {
                reviewRepository.save(entity);
            }
        }
    }
}
