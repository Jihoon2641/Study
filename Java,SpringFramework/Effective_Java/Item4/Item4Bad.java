// ① 흔히 쓰는(나쁜) 코드 — ①-1 생성자를 아예 안 씀, ①-2 abstract 로 막으려 함
// 대상 Java 버전: 5+ → Java 7 레거시에서도 그대로 동작
// 실행 진입점은 Item4Problem.java 다.

public class Item4Bad {

    // ①-1 생성자를 아예 쓰지 않았다 — "정적 메서드뿐이니 아무도 인스턴스를 못 만들겠지"
    public static class MathUtils {
        static double round(double v) {
            return Math.floor(v + 0.5);   // 규칙: 0.5는 올림
        }
    }

    // ①-2 "추상 클래스로 만들면 인스턴스화가 막히겠지"
    public abstract static class AbstractMathUtils {
        static double round(double v) {
            return Math.floor(v + 0.5);
        }
    }

    // ①-2의 결말 — 하위 클래스 한 줄이면 인스턴스가 생긴다. abstract는 오히려 상속하라는 신호로 읽힌다.
    static class ReportService extends AbstractMathUtils {
        // 시그니처가 같은 정적 메서드는 재정의(overriding)가 아니라 은닉(hiding)이다
        static double round(double v) {
            return Math.rint(v);          // 규칙을 바꿨다: 짝수로 반올림
        }
        // @Override static double round(double v) { ... }
        // 컴파일 에러: static methods cannot be annotated with @Override
        // → 재정의가 아니라는 사실을 @Override 로 확인할 수조차 없다
    }
}
