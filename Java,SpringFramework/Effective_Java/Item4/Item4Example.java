// 대상 Java 버전: 5+ (리플렉션·정적 중첩 클래스만 사용). Java 7 레거시에서도 그대로 동작 → Legacy7 불필요.
// 실행: java Item4/Item4Example.java  (단일 파일 소스 실행은 Java 11+)

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

public class Item4Example {

    // ①-1 생성자를 아예 쓰지 않았다 — "정적 메서드뿐이니 아무도 인스턴스를 못 만들겠지"
    public static class MathUtils {
        static double round(double v) { return Math.floor(v + 0.5); }   // 규칙: 0.5는 올림
    }

    // ①-2 "추상 클래스로 만들면 인스턴스화가 막히겠지"
    public abstract static class AbstractMathUtils {
        static double round(double v) { return Math.floor(v + 0.5); }
    }

    // ①-2의 결말 — 하위 클래스 한 줄이면 인스턴스가 생긴다. abstract는 오히려 상속하라는 신호로 읽힌다.
    static class ReportService extends AbstractMathUtils {
        // 시그니처가 같은 정적 메서드는 재정의(overriding)가 아니라 은닉(hiding)이다
        static double round(double v) { return Math.rint(v); }          // 규칙을 바꿨다: 짝수로 반올림
        // @Override static double round(double v) { ... }
        // 컴파일 에러: static methods cannot be annotated with @Override
        // → 재정의가 아니라는 사실을 @Override 로 확인할 수조차 없다
    }

    // ③ 개선된 코드 — private 생성자로 기본 생성자 생성을 막고, 내부 호출까지 차단한다
    static class GoodMathUtils {
        private GoodMathUtils() { throw new AssertionError("인스턴스화 금지"); }
        static double round(double v) { return Math.floor(v + 0.5); }
    }

    // ② 무엇이 문제인가
    public static void main(String[] args) throws Exception {
        System.out.println("=== ①-1 생성자를 안 썼는데 생성자가 있다 ===");
        Constructor<?>[] ctors = MathUtils.class.getDeclaredConstructors();
        System.out.println("소스에 쓴 생성자 = 0개, 클래스 파일의 생성자 = " + ctors.length
                + "개, public? " + Modifier.isPublic(ctors[0].getModifiers()));
        // 출력: 소스에 쓴 생성자 = 0개, 클래스 파일의 생성자 = 1개, public? true
        MathUtils u = new MathUtils();          // 컴파일 통과, 예외 없음. 아무 의미 없는 객체가 생긴다.
        System.out.println("new MathUtils() 성공 → " + u.getClass().getSimpleName());

        System.out.println();
        System.out.println("=== ①-2 abstract 는 인스턴스화를 못 막는다 ===");
        // AbstractMathUtils a = new AbstractMathUtils();
        // 컴파일 에러: AbstractMathUtils is abstract; cannot be instantiated  ← 여기까지만 막힌다
        AbstractMathUtils viaSubclass = new ReportService();   // 하위 클래스로는 그냥 만들어진다
        System.out.println("new ReportService() 성공 → " + viaSubclass.getClass().getSimpleName());

        System.out.println();
        System.out.println("=== ①-2 상속을 허용한 대가: 정적 메서드 은닉 ===");
        System.out.println("ReportService.round(2.5) = " + ReportService.round(2.5));
        // 출력: 2.0  ← 새 규칙(짝수 반올림)
        System.out.println("viaSubclass.round(2.5)   = " + viaSubclass.round(2.5));
        // 출력: 3.0  ← 같은 객체인데 참조 타입이 AbstractMathUtils 라서 옛 규칙이 불린다.
        //             정적 메서드는 객체가 아니라 컴파일 시점의 참조 타입으로 결정되기 때문.
        //             컴파일 에러 없음. 런타임 예외 없음. 로그 한 줄 안 남는다.

        System.out.println();
        System.out.println("=== ③ private 생성자 + AssertionError ===");
        try {
            new GoodMathUtils();   // 같은 파일(같은 nest) 안이라 private 이어도 컴파일된다 — 이 실수를 막는 게 목적
        } catch (AssertionError e) {
            System.out.println("클래스 내부의 실수도 차단: " + e.getMessage());
            // 출력: 클래스 내부의 실수도 차단: 인스턴스화 금지
            // assert 문이 아니라 명시적 throw 이므로 -da(어서션 비활성화)로 실행해도 그대로 던진다
        }
        Constructor<GoodMathUtils> c = GoodMathUtils.class.getDeclaredConstructor();
        c.setAccessible(true);
        try {
            c.newInstance();
        } catch (InvocationTargetException e) {
            System.out.println("리플렉션도 차단: " + e.getCause());
            // 출력: 리플렉션도 차단: java.lang.AssertionError: 인스턴스화 금지
        }

        // 다른 톱레벨 클래스에서:
        //   class Sub extends GoodMathUtils { }
        //   컴파일 에러: GoodMathUtils() has private access in GoodMathUtils
        //   → 상속이 막히는 것은 private 생성자의 부가 효과다
    }
}
