// ② 무엇이 문제인가 — ① Item15Bad 를 실제로 깨뜨리고, ③ Item15Good·Item15Legacy7 과 나란히 비교한다
// 대상 Java 버전: 7+ (이 파일 자체는 Java 7 문법. Item15Good 이 9+ 라서 폴더 전체는 9+ 로 컴파일한다)
// 실행 (Java 11~21): javac -d out/cli/Item15 Item15/*.java && java -cp out/cli/Item15 Item15Problem
// 실행 (Java 22+):   java Item15/Item15Problem.java   ← 같은 폴더의 다른 소스 파일을 자동으로 찾는다

import java.util.Arrays;

public class Item15Problem {

    public static void main(String[] args) {
        System.out.println("=== ① Bad: public static final 배열 ===");
        System.out.println("앱 시작 시점 Bad.VALUES = " + Arrays.toString(Item15Bad.VALUES));
        // 출력: [ONE, TWO]

        // 남의 클래스가 이 한 줄만 실행하면 전역 상수가 영구히 오염된다.
        // aastore 명령이라 final과 무관 — 컴파일러도 JVM도 막지 않는다.
        Item15Bad.VALUES[0] = new Item15Common.Thing("HACKED");

        System.out.println("외부 코드가 한 줄 건드린 뒤 = " + Arrays.toString(Item15Bad.VALUES));
        // 출력: [HACKED, TWO]  ← 이후 이 상수를 읽는 모든 코드가 오염된 값을 본다

        // Item15Bad.VALUES = new Item15Common.Thing[0];
        // 컴파일 에러: cannot assign a value to final variable VALUES
        // → 참조 재대입만 막힌다. 원소 변경은 위처럼 그대로 통과한다.

        System.out.println();
        System.out.println("=== ③ Good: List.of 불변 리스트 (Java 9+) ===");
        try {
            Item15Good.Good.VALUES.set(0, new Item15Common.Thing("HACKED"));
        } catch (UnsupportedOperationException e) {
            System.out.println("set(0, ...) → UnsupportedOperationException 으로 차단됨");
        }
        System.out.println("Good.VALUES = " + Item15Good.Good.VALUES);
        // 출력: [ONE, TWO]

        System.out.println();
        System.out.println("=== ③ GoodClone: 방어적 복사본 반환 ===");
        Item15Common.Thing[] copy = Item15Good.GoodClone.values();
        copy[0] = new Item15Common.Thing("HACKED");          // 호출자가 받은 건 복사본이다
        System.out.println("호출자가 고친 복사본 = " + Arrays.toString(copy));
        // 출력: [HACKED, TWO]
        System.out.println("클래스 내부 원본     = " + Arrays.toString(Item15Good.GoodClone.values()));
        // 출력: [ONE, TWO]  ← 원본은 그대로

        System.out.println();
        System.out.println("=== Legacy7: Java 7에서의 같은 방어 ===");
        try {
            Item15Legacy7.VALUES.set(0, new Item15Common.Thing("HACKED"));
        } catch (UnsupportedOperationException e) {
            System.out.println("set(0, ...) → UnsupportedOperationException 으로 차단됨");
        }
        System.out.println("Legacy7.VALUES = " + Item15Legacy7.VALUES);
        // 출력: [ONE, TWO]
        // 내부 배열 PRIVATE_VALUES 는 private 이라 애초에 외부에서 이름조차 못 부른다.
    }
}
