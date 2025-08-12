package likelion.jsondata.record;

public record ReviewJson(
        String restaurant_name,
        String category,
        String review_rating,
        String review_content,
        String review_url
) { }
