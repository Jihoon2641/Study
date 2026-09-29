// ① 흔히 쓰는(나쁜) 코드 — 상속용 클래스의 생성자가 재정의 가능 메서드를 호출한다
// 대상 Java 버전: 8+ (java.time.Instant). Java 7 에서는 Instant 대신 java.util.Date 로 똑같이 재현된다
// 실행 진입점은 Item19Problem.java 다.

import java.time.Instant;

public class Item19Bad {

    // 상위 클래스 작성자(프레임워크 팀)가 만든 상속용 클래스
    static class Super {
        // 잘못된 예 — 생성자가 재정의 가능 메서드를 호출한다
        public Super() {
            overrideMe();
        }

        public void overrideMe() {
        }
    }

    // 하위 클래스 작성자(다른 팀)가 만든 클래스 — 책의 예제 그대로
    static final class Sub extends Super {
        // 초기화 일관성을 위해 생성자에서 초기화한다
        private final Instant instant;

        Sub() {
            instant = Instant.now();   // 이 줄은 Super() 가 끝난 "뒤에" 실행된다
        }

        // 상위 클래스의 생성자가 호출하는 재정의 메서드
        @Override
        public void overrideMe() {
            System.out.println("overrideMe: instant = " + instant);
        }
    }

    // 같은 상황에서 필드를 역참조하는 하위 클래스 — println 처럼 null 을 봐주지 않는다
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
