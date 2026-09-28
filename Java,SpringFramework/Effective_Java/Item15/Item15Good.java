// ③ 개선된 코드 — (1) 불변 리스트로 공개, (2) 배열을 유지해야 하면 방어적 복사본 반환
// 대상 Java 버전: 9+ (List.of). Java 7 대안은 Item15Legacy7.java
// 실행 진입점은 Item15Problem.java 다.

import java.util.List;

public class Item15Good {

    // ③ (1) — 애초에 불변 리스트로 공개한다 (Java 9+)
    static final class Good {
        public static final List<Item15Common.Thing> VALUES =
                List.of(new Item15Common.Thing("ONE"), new Item15Common.Thing("TWO"));
    }

    // ③ (2) — 배열 타입을 유지해야 한다면 방어적 복사본을 반환한다
    static final class GoodClone {
        private static final Item15Common.Thing[] PRIVATE_VALUES =
                {new Item15Common.Thing("ONE"), new Item15Common.Thing("TWO")};

        public static Item15Common.Thing[] values() {
            return PRIVATE_VALUES.clone();
        }
    }
}
