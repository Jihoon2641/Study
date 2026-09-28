// ③ 개선된 코드 — 비교자 생성 메서드 연쇄
// 대상 Java 버전: 8+ (람다, Comparator.comparingInt). Java 7 대안은 Item14Legacy7.java
// 실행 진입점은 Item14Problem.java 다.

import java.util.Comparator;

public class Item14Good {

    static final class GoodNode implements Comparable<GoodNode> {
        private static final Comparator<GoodNode> COMPARATOR =
                Comparator.comparingInt((GoodNode n) -> n.hash)
                        .thenComparing(n -> n.name); // 1차 키가 같을 때만 2차 키를 본다

        final String name;
        final int hash;

        GoodNode(String name, int hash) {
            this.name = name;
            this.hash = hash;
        }

        @Override
        public int compareTo(GoodNode o) {
            return COMPARATOR.compare(this, o);
        }

        @Override
        public String toString() {
            return name + "(" + hash + ")";
        }
    }
}
