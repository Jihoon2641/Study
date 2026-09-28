// ② 무엇이 문제인가 — ① Item4Bad 를 실제로 깨뜨리고, ③ Item4Good 과 나란히 비교한다
// 대상 Java 버전: 5+ (리플렉션·정적 중첩 클래스만 사용) → Java 7 레거시에서도 그대로 동작
// 실행 (Java 11~21): javac -d out/cli/Item4 Item4/*.java && java -cp out/cli/Item4 Item4Problem
// 실행 (Java 22+):   java Item4/Item4Problem.java   ← 같은 폴더의 다른 소스 파일을 자동으로 찾는다

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

public class Item4Problem {

    public static void main(String[] args) throws Exception {
        System.out.println("=== ①-1 생성자를 안 썼는데 생성자가 있다 ===");
        Constructor<?>[] ctors = Item4Bad.MathUtils.class.getDeclaredConstructors();
        System.out.println("소스에 쓴 생성자 = 0개, 클래스 파일의 생성자 = " + ctors.length
                + "개, public? " + Modifier.isPublic(ctors[0].getModifiers()));
        // 출력: 소스에 쓴 생성자 = 0개, 클래스 파일의 생성자 = 1개, public? true
        Item4Bad.MathUtils u = new Item4Bad.MathUtils();   // 컴파일 통과, 예외 없음. 아무 의미 없는 객체가 생긴다.
        System.out.println("new MathUtils() 성공 → " + u.getClass().getSimpleName());

        System.out.println();
        System.out.println("=== ①-2 abstract 는 인스턴스화를 못 막는다 ===");
        // Item4Bad.AbstractMathUtils a = new Item4Bad.AbstractMathUtils();
        // 컴파일 에러: AbstractMathUtils is abstract; cannot be instantiated  ← 여기까지만 막힌다
        Item4Bad.AbstractMathUtils viaSubclass = new Item4Bad.ReportService();   // 하위 클래스로는 그냥 만들어진다
        System.out.println("new ReportService() 성공 → " + viaSubclass.getClass().getSimpleName());

        System.out.println();
        System.out.println("=== ①-2 상속을 허용한 대가: 정적 메서드 은닉 ===");
        System.out.println("ReportService.round(2.5) = " + Item4Bad.ReportService.round(2.5));
        // 출력: 2.0  ← 새 규칙(짝수 반올림)
        System.out.println("viaSubclass.round(2.5)   = " + viaSubclass.round(2.5));
        // 출력: 3.0  ← 같은 객체인데 참조 타입이 AbstractMathUtils 라서 옛 규칙이 불린다.
        //             정적 메서드는 객체가 아니라 컴파일 시점의 참조 타입으로 결정되기 때문.
        //             컴파일 에러 없음. 런타임 예외 없음. 로그 한 줄 안 남는다.

        System.out.println();
        System.out.println("=== ③ private 생성자 + AssertionError ===");
        // new Item4Good.GoodMathUtils();
        // 컴파일 에러: GoodMathUtils() has private access in GoodMathUtils  ← 바깥에서는 컴파일부터 막힌다
        try {
            Item4Good.GoodMathUtils.internalMistake();       // 클래스 내부의 new 는 컴파일이 되므로 실행 시점에 막는다
        } catch (AssertionError e) {
            System.out.println("클래스 내부의 실수도 차단: " + e.getMessage());
            // 출력: 클래스 내부의 실수도 차단: 인스턴스화 금지
            // assert 문이 아니라 명시적 throw 이므로 -da(어서션 비활성화)로 실행해도 그대로 던진다
        }
        Constructor<Item4Good.GoodMathUtils> c = Item4Good.GoodMathUtils.class.getDeclaredConstructor();
        c.setAccessible(true);
        try {
            c.newInstance();
        } catch (InvocationTargetException e) {
            System.out.println("리플렉션도 차단: " + e.getCause());
            // 출력: 리플렉션도 차단: java.lang.AssertionError: 인스턴스화 금지
        }
    }

    // static class Sub extends Item4Good.GoodMathUtils { }
    // 컴파일 에러: GoodMathUtils() has private access in GoodMathUtils
    // → 상속이 막히는 것은 private 생성자의 부가 효과다
}
