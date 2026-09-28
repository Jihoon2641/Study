// ③ 개선된 코드 — private 생성자로 기본 생성자 생성을 막고, 내부 호출까지 차단한다
// 대상 Java 버전: 5+ → Java 7 레거시에서도 그대로 동작
// 실행 진입점은 Item4Problem.java 다.

public class Item4Good {

    static class GoodMathUtils {
        private GoodMathUtils() {
            throw new AssertionError("인스턴스화 금지");
        }

        static double round(double v) {
            return Math.floor(v + 0.5);
        }

        // 재현용 — 클래스 내부에서 실수로 인스턴스를 만드는 코드.
        // private 생성자라도 같은 클래스 안이라 컴파일된다. AssertionError 가 막는 것이 바로 이 경우다.
        static GoodMathUtils internalMistake() {
            return new GoodMathUtils();
        }
    }
}
