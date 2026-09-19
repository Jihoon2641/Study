// 대상 Java 버전: 8+ (Java 7 대안은 하단 Legacy7 참고)
// 실행: java Item14Example.java   (Java 17 이하에서 한글 주석/출력이 깨지면 javac -encoding UTF-8)

import java.util.Comparator;
import java.util.TreeSet;

public class Item14Example {

    // ① 흔히 쓰는(나쁜) 코드 — 두 값의 차를 그대로 반환한다
    static final class BadNode implements Comparable<BadNode> {
        final String name;
        final int hash;

        BadNode(String name, int hash) { this.name = name; this.hash = hash; }

        @Override public int compareTo(BadNode o) {
            return hash - o.hash; // 뺄셈 결과가 int 범위를 넘으면 부호가 통째로 뒤집힌다
        }
        @Override public String toString() { return name + "(" + hash + ")"; }
    }

    // ③ 개선된 코드 — 비교자 생성 메서드 연쇄 (Java 8+)
    static final class GoodNode implements Comparable<GoodNode> {
        private static final Comparator<GoodNode> COMPARATOR =
                Comparator.comparingInt((GoodNode n) -> n.hash)
                          .thenComparing(n -> n.name); // 1차 키가 같을 때만 2차 키를 본다

        final String name;
        final int hash;

        GoodNode(String name, int hash) { this.name = name; this.hash = hash; }

        @Override public int compareTo(GoodNode o) { return COMPARATOR.compare(this, o); }
        @Override public String toString() { return name + "(" + hash + ")"; }
    }

    // Java 7 대안 — 람다도 Comparator 정적 메서드도 없이 같은 순서를 만든다
    static final class Legacy7 implements Comparable<Legacy7> {
        final String name;
        final int hash;

        Legacy7(String name, int hash) { this.name = name; this.hash = hash; }

        @Override public int compareTo(Legacy7 o) {
            int result = Integer.compare(hash, o.hash); // Integer.compare는 Java 7+
            if (result == 0) result = name.compareTo(o.name);
            return result;
        }
        @Override public String toString() { return name + "(" + hash + ")"; }
    }

    // ② 무엇이 문제인가 — 말로 때우지 말고 여기서 실제로 깨뜨린다
    public static void main(String[] args) {
        BadNode big  = new BadNode("BIG",  Integer.MAX_VALUE);
        BadNode neg  = new BadNode("NEG",  -1);
        BadNode zero = new BadNode("ZERO", 0);

        // (1) 대칭성 위반 — 서로가 서로에게 "내가 더 작다"고 말한다
        System.out.println("BIG.compareTo(NEG) = " + big.compareTo(neg)); // 출력: -2147483648 (음수)
        System.out.println("NEG.compareTo(BIG) = " + neg.compareTo(big)); // 출력: -2147483648 (음수)
        // 2147483647 - (-1) 은 2147483648 이어야 하지만 int를 넘어 MIN_VALUE로 감긴다

        // (2) 그 결과 TreeSet의 순서가 통째로 무너진다. 예외는 안 난다 — 조용히 틀린 답만 나온다
        TreeSet<BadNode> bad = new TreeSet<>();
        bad.add(neg); bad.add(zero); bad.add(big); // 이 삽입 순서에서 BIG이 NEG의 왼쪽에 박힌다
        System.out.println("bad          = " + bad);              // 출력: [BIG(2147483647), NEG(-1), ZERO(0)]
        System.out.println("bad.first()  = " + bad.first());      // 출력: BIG(2147483647)  ← 최솟값이 MAX_VALUE
        System.out.println("ZERO 미만    = " + bad.headSet(zero));// 출력: [BIG(2147483647), NEG(-1)]

        // (3) Integer.compare로 바꾸면 순서가 제자리를 찾는다
        TreeSet<GoodNode> good = new TreeSet<>();
        good.add(new GoodNode("NEG", -1));
        good.add(new GoodNode("ZERO", 0));
        good.add(new GoodNode("BIG", Integer.MAX_VALUE));
        System.out.println("good         = " + good);         // 출력: [NEG(-1), ZERO(0), BIG(2147483647)]
        System.out.println("good.first() = " + good.first()); // 출력: NEG(-1)

        // (4) Java 7 대안도 결과가 같다
        TreeSet<Legacy7> legacy = new TreeSet<>();
        legacy.add(new Legacy7("NEG", -1));
        legacy.add(new Legacy7("ZERO", 0));
        legacy.add(new Legacy7("BIG", Integer.MAX_VALUE));
        System.out.println("legacy7      = " + legacy);       // 출력: [NEG(-1), ZERO(0), BIG(2147483647)]
    }
}
