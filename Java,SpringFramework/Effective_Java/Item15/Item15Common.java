// ①·③ 공용 타입 — Item15Bad, Item15Good, Item15Legacy7 이 함께 쓰는 값 객체
// 대상 Java 버전: 1.0+ → Java 7 레거시에서도 그대로 컴파일된다
// 실행 진입점은 Item15Problem.java 다.

public class Item15Common {

    /**
     * 책의 Thing 역할 — 상수 목록에 담기는 값 객체(불변)
     */
    static final class Thing {
        private final String name;

        Thing(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
