// ② 무엇이 문제인가 — ① Item18Bad 를 실제로 깨뜨리고, ③ Item18Good 과 나란히 비교한다
// 대상 Java 버전: 7+ (책의 List.of 대신 Arrays.asList 를 써서 Java 7 문법만 사용)
// 실행 (Java 11~21): javac -d out/cli/Item18 Item18/*.java && java -cp out/cli/Item18 Item18Problem
// 실행 (Java 22+):   java Item18/Item18Problem.java   ← 같은 폴더의 다른 소스 파일을 자동으로 찾는다

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

public class Item18Problem {

    public static void main(String[] args) {
        List<String> words = Arrays.asList("틱", "탁탁", "펑");   // 책: List.of (Java 9+)

        System.out.println("=== ① 상속: InstrumentedHashSet ===");
        Item18Bad.InstrumentedHashSet<String> bad = new Item18Bad.InstrumentedHashSet<>();
        bad.add("하나");
        System.out.println("add 1번 → addCount = " + bad.getAddCount());
        // 출력: add 1번 → addCount = 1  ← add 만 쓰는 테스트는 통과한다

        bad = new Item18Bad.InstrumentedHashSet<>();
        bad.addAll(words);
        System.out.println("addAll(3개) → addCount = " + bad.getAddCount() + ", 실제 원소 수 = " + bad.size());
        // 출력: addAll(3개) → addCount = 6, 실제 원소 수 = 3
        //       ← 컴파일 에러도, 예외도, 로그도 없다. 숫자만 조용히 두 배가 된다.
        //         원인은 HashSet(정확히는 AbstractCollection)의 문서화되지 않은 구현 세부다.

        System.out.println();
        System.out.println("=== ③ 컴포지션: InstrumentedSet ===");
        Item18Good.InstrumentedSet<String> good = new Item18Good.InstrumentedSet<>(new HashSet<String>());
        good.addAll(words);
        System.out.println("addAll(3개) → addCount = " + good.getAddCount() + ", 실제 원소 수 = " + good.size());
        // 출력: addAll(3개) → addCount = 3, 실제 원소 수 = 3

        // 덤: 어떤 Set 구현이든 감쌀 수 있다. InstrumentedHashSet 은 HashSet 에만 붙는다.
        Item18Good.InstrumentedSet<String> sorted = new Item18Good.InstrumentedSet<>(new TreeSet<String>());
        sorted.addAll(words);
        sorted.add("틱");   // 중복이라 실제로는 추가되지 않지만 "시도 횟수"는 센다
        System.out.println("TreeSet 감싸기 → " + sorted + ", addCount = " + sorted.getAddCount());
        // 출력: TreeSet 감싸기 → [탁탁, 틱, 펑], addCount = 4
    }
}
