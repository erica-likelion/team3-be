package likelion.service.distance;

public class DistanceCalc {
    private static final double EARTH_RADIUS = 6371000;

    public static double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        // 위도, 경도를 라디안 값으로 변환
        double lat1Rad = Math.toRadians(lat1);
        double lon1Rad = Math.toRadians(lon1);
        double lat2Rad = Math.toRadians(lat2);
        double lon2Rad = Math.toRadians(lon2);

        // 위도, 경도 차이 계산
        double dLat = lat2Rad - lat1Rad;
        double dLon = lon2Rad - lon1Rad;

        // Haversine 공식 적용
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1Rad) * Math.cos(lat2Rad)
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        // 지구 반지름을 곱해서 거리 계산 (단위: 미터)
        return EARTH_RADIUS * c;
    }
}
