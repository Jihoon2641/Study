// ③ 개선된 코드 — 생성자는 재정의 가능 메서드 대신 private 도우미 메서드만 호출한다
// 대상 Java 버전: 8+ (java.time.Instant). Java 7 에서는 Instant 대신 java.util.Date 로 똑같이 동작한다
// 실행 진입점은 Item19Problem.java 다.

import java.time.Instant;

public class Item19Good {

    static class Super {
        public Super() {
            initHelper();   // private 이라 재정의될 수 없다 → 하위 클래스 코드가 끼어들 틈이 없다
        }

        /**
         * 하위 클래스가 재정의할 수 있다.
         *
         * @implSpec 이 구현은 initHelper 를 호출한다. 생성자는 이 메서드를 호출하지 않는다.
         */
        public void overrideMe() {
            initHelper();
        }

        // 원래 overrideMe 본문에 있던 코드를 옮겨 둔 도우미 메서드
        private void initHelper() {
        }
    }

    // 하위 클래스는 ① 과 한 글자도 다르지 않다 — 고친 쪽은 상위 클래스뿐이다
    static final class Sub extends Super {
        private final Instant instant;

        Sub() {
            instant = Instant.now();
        }

        @Override
        public void overrideMe() {
            System.out.println("overrideMe: instant = " + instant);
        }
    }

    static final class StrictSub extends Super {
        private final Instant instant;

        StrictSub() {
            instant = Instant.now();
        }

        @Override
        public void overrideMe() {
            System.out.println("overrideMe: epochSecond = " + instant.getEpochSecond());
        }
    }
}
