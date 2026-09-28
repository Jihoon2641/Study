// ③ 개선된 코드 — ③-1 생성자 가드 + readResolve, ③-2 원소 하나짜리 열거 타입
// 대상 Java 버전: 5+ 문법만 사용 → Java 7 레거시에서도 그대로 동작
// 실행 진입점은 Item3Problem.java 다.

import java.io.Serializable;

public class Item3Good {

    // ③-1 클래스 방식을 유지해야 할 때 — 생성자 가드(리플렉션 방어) + readResolve(직렬화 방어)
    static class GuardedElvis implements Serializable {
        public static final GuardedElvis INSTANCE = new GuardedElvis();

        private GuardedElvis() {
            if (INSTANCE != null) throw new IllegalStateException("두 번째 GuardedElvis 생성 시도");
        }

        private Object readResolve() {
            return INSTANCE;    // 역직렬화로 생긴 객체는 버리고 진짜를 돌려준다
        }
    }

    // ③-2 책이 권하는 방식 — 원소 하나짜리 열거 타입. 방어 코드가 한 줄도 없다.
    enum EnumElvis {INSTANCE}
}
