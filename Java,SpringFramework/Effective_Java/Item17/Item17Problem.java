// ② 무엇이 문제인가 — ① Item17Bad 를 실제로 깨뜨리고, ③ Item17Good 과 나란히 비교한다
// 대상 Java 버전: 7+
// 실행 (Java 11~21): javac -d out/cli/Item17 Item17/*.java && java -cp out/cli/Item17 Item17Problem
// 실행 (Java 22+):   java Item17/Item17Problem.java   ← 같은 폴더의 다른 소스 파일을 자동으로 찾는다

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Item17Problem {

    // 다른 모듈의 합계 유틸 — "0부터 더한다"는 자연스러운 코드
    static Item17Bad.MutableComplex sumBad(List<Item17Bad.MutableComplex> xs) {
        Item17Bad.MutableComplex sum = Item17Bad.MutableComplex.ZERO;   // 상수를 누산기 시작값으로 썼다
        for (Item17Bad.MutableComplex x : xs) sum.add(x);
        return sum;
    }

    static Item17Good.Complex sumGood(List<Item17Good.Complex> xs) {
        Item17Good.Complex sum = Item17Good.Complex.ZERO;
        for (Item17Good.Complex x : xs) sum = sum.plus(x);     // 결과를 다시 대입해야 한다
        return sum;
    }

    public static void main(String[] args) {
        System.out.println("=== ① MutableComplex: 공유한 상수가 오염된다 ===");
        System.out.println("1차 합계 = " + sumBad(Arrays.asList(
                new Item17Bad.MutableComplex(1, 1), new Item17Bad.MutableComplex(2, 2))));
        // 출력: 1차 합계 = (3.0 + 3.0i)  ← 첫 호출은 맞다. 단위 테스트도 한 번만 호출해서 통과한다
        System.out.println("2차 합계 = " + sumBad(Arrays.asList(new Item17Bad.MutableComplex(1, 0))));
        // 출력: 2차 합계 = (4.0 + 3.0i)  ← 기대값 (1.0 + 0.0i). 이전 호출의 결과가 섞였다
        System.out.println("ZERO     = " + Item17Bad.MutableComplex.ZERO);
        // 출력: ZERO     = (4.0 + 3.0i)  ← 이제 ZERO 를 쓰는 모든 코드가 틀린다. 예외는 없다

        Item17Bad.MutableComplex key = new Item17Bad.MutableComplex(1, 1);
        Set<Item17Bad.MutableComplex> seen = new HashSet<>();
        seen.add(key);
        key.add(new Item17Bad.MutableComplex(1, 0));          // 집합에 넣은 뒤 원소를 바꿨다
        System.out.println("seen.contains(key) = " + seen.contains(key) + ", size = " + seen.size());
        // 출력: seen.contains(key) = false, size = 1  ← 원소는 들어 있는데 찾을 수 없다
        //        (옛 해시 버킷에 남아 있어서 새 hashCode 로는 도달하지 못한다)

        System.out.println();
        System.out.println("=== ③ Complex: 공유해도 안전하다 ===");
        System.out.println("1차 합계 = " + sumGood(Arrays.asList(
                Item17Good.Complex.valueOf(1, 1), Item17Good.Complex.valueOf(2, 2))));
        // 출력: 1차 합계 = (3.0 + 3.0i)
        System.out.println("2차 합계 = " + sumGood(Arrays.asList(Item17Good.Complex.valueOf(1, 0))));
        // 출력: 2차 합계 = (1.0 + 0.0i)  ← 호출 순서와 무관하게 같은 답
        System.out.println("ZERO     = " + Item17Good.Complex.ZERO);
        // 출력: ZERO     = (0.0 + 0.0i)

        Item17Good.Complex k = Item17Good.Complex.valueOf(1, 1);
        Set<Item17Good.Complex> seen2 = new HashSet<>();
        seen2.add(k);
        k.plus(Item17Good.Complex.valueOf(1, 0));              // 반환값을 버렸다 — k 는 그대로다
        System.out.println("seen2.contains(k) = " + seen2.contains(k));
        // 출력: seen2.contains(k) = true
    }
}
