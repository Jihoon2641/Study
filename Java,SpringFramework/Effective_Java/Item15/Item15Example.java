// 대상 Java 버전: 9+ (List.of 사용). Java 7 대안은 하단 Legacy7 참고.
// 실행: java Item15/Item15Example.java  (단일 파일 소스 실행은 Java 11+)

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Item15Example {

    /** 책의 Thing 역할 — 상수 목록에 담기는 값 객체(불변) */
    static final class Thing {
        private final String name;
        Thing(String name) { this.name = name; }
        @Override public String toString() { return name; }
    }

    // ① 흔히 쓰는(나쁜) 코드 — "상수니까 public static final이면 되겠지"
    static final class Bad {
        // final은 '참조'만 고정한다. 배열 원소는 패키지 밖 누구든 바꿀 수 있다.
        public static final Thing[] VALUES = { new Thing("ONE"), new Thing("TWO") };
    }

    // ③ 개선된 코드 (1) — 애초에 불변 리스트로 공개한다 (Java 9+)
    static final class Good {
        public static final List<Thing> VALUES = List.of(new Thing("ONE"), new Thing("TWO"));
    }

    // ③ 개선된 코드 (2) — 배열 타입을 유지해야 한다면 방어적 복사본을 반환한다
    static final class GoodClone {
        private static final Thing[] PRIVATE_VALUES = { new Thing("ONE"), new Thing("TWO") };
        public static Thing[] values() { return PRIVATE_VALUES.clone(); }
    }

    // Java 7 대안 — List.of가 없으니 배열을 private으로 감추고 불변 뷰만 공개한다 (Java 1.2+)
    static final class Legacy7 {
        private static final Thing[] PRIVATE_VALUES = { new Thing("ONE"), new Thing("TWO") };
        public static final List<Thing> VALUES =
                Collections.unmodifiableList(Arrays.asList(PRIVATE_VALUES));
    }

    // ② 무엇이 문제인가 — 말로 때우지 말고 여기서 실제로 깨뜨린다
    public static void main(String[] args) {
        System.out.println("=== ① Bad: public static final 배열 ===");
        System.out.println("앱 시작 시점 Bad.VALUES = " + Arrays.toString(Bad.VALUES));
        // 출력: [ONE, TWO]

        // 남의 클래스가 이 한 줄만 실행하면 전역 상수가 영구히 오염된다.
        // aastore 명령이라 final과 무관 — 컴파일러도 JVM도 막지 않는다.
        Bad.VALUES[0] = new Thing("HACKED");

        System.out.println("외부 코드가 한 줄 건드린 뒤 = " + Arrays.toString(Bad.VALUES));
        // 출력: [HACKED, TWO]  ← 이후 이 상수를 읽는 모든 코드가 오염된 값을 본다

        // Bad.VALUES = new Thing[0];
        // 컴파일 에러: cannot assign a value to final variable VALUES
        // → 참조 재대입만 막힌다. 원소 변경은 위처럼 그대로 통과한다.

        System.out.println();
        System.out.println("=== ③ Good: List.of 불변 리스트 (Java 9+) ===");
        try {
            Good.VALUES.set(0, new Thing("HACKED"));
        } catch (UnsupportedOperationException e) {
            System.out.println("set(0, ...) → UnsupportedOperationException 으로 차단됨");
        }
        System.out.println("Good.VALUES = " + Good.VALUES);
        // 출력: [ONE, TWO]

        System.out.println();
        System.out.println("=== ③ GoodClone: 방어적 복사본 반환 ===");
        Thing[] copy = GoodClone.values();
        copy[0] = new Thing("HACKED");          // 호출자가 받은 건 복사본이다
        System.out.println("호출자가 고친 복사본 = " + Arrays.toString(copy));
        // 출력: [HACKED, TWO]
        System.out.println("클래스 내부 원본     = " + Arrays.toString(GoodClone.values()));
        // 출력: [ONE, TWO]  ← 원본은 그대로

        System.out.println();
        System.out.println("=== Legacy7: Java 7에서의 같은 방어 ===");
        try {
            Legacy7.VALUES.set(0, new Thing("HACKED"));
        } catch (UnsupportedOperationException e) {
            System.out.println("set(0, ...) → UnsupportedOperationException 으로 차단됨");
        }
        System.out.println("Legacy7.VALUES = " + Legacy7.VALUES);
        // 출력: [ONE, TWO]
        // 내부 배열 PRIVATE_VALUES 는 private 이라 애초에 외부에서 이름조차 못 부른다.
    }
}
