// Java 7 대안 — 람다도 Comparator 정적 메서드도 없이 ③ Item14Good 과 같은 순서를 만든다
// 대상 Java 버전: 7+ (Integer.compare 는 Java 7+)
// 실행 진입점은 Item14Problem.java 다.

public class Item14Legacy7 {

    static final class LegacyNode implements Comparable<LegacyNode> {
        final String name;
        final int hash;

        LegacyNode(String name, int hash) {
            this.name = name;
            this.hash = hash;
        }

        @Override
        public int compareTo(LegacyNode o) {
            int result = Integer.compare(hash, o.hash);
            if (result == 0) result = name.compareTo(o.name);
            return result;
        }

        @Override
        public String toString() {
            return name + "(" + hash + ")";
        }
    }
}
