// ① 흔히 쓰는(나쁜) 코드 — 책의 Boolean 을 흉내 내되, public 생성자만 열어 두었다
// 대상 Java 버전: 7+ (Java 7 레거시에서도 그대로 컴파일된다)
// 실행 진입점은 Item1Problem.java 다.

public class Item1Bad {

    static final class BadBoolean {
        static int created = 0;                               // 생성 횟수 측정용
        public static final BadBoolean TRUE = new BadBoolean(true);
        public static final BadBoolean FALSE = new BadBoolean(false);

        private final boolean value;

        public BadBoolean(boolean value) {
            this.value = value;
            created++;                                        // 호출할 때마다 무조건 새 객체
        }

        boolean booleanValue() {
            return value;
        }
    }
}
