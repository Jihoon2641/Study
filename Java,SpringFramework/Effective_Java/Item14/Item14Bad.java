// ① 흔히 쓰는(나쁜) 코드 — 두 값의 차를 그대로 반환한다
// 대상 Java 버전: 5+ → Java 7 레거시에서도 그대로 컴파일된다
// 실행 진입점은 Item14Problem.java 다.

public class Item14Bad {

    static final class BadNode implements Comparable<BadNode> {
        final String name;
        final int hash;

        BadNode(String name, int hash) {
            this.name = name;
            this.hash = hash;
        }

        @Override
        public int compareTo(BadNode o) {
            return hash - o.hash; // 뺄셈 결과가 int 범위를 넘으면 부호가 통째로 뒤집힌다
        }

        @Override
        public String toString() {
            return name + "(" + hash + ")";
        }
    }
}
