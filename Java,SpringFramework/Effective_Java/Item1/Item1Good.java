// ③ 개선된 코드 — 생성자를 막고, 정적 팩터리 메서드로만 인스턴스를 내준다 (책의 Boolean.valueOf)
// 대상 Java 버전: 7+ (Java 7 레거시에서도 그대로 컴파일된다)
// 실행 진입점은 Item1Problem.java 다.

public class Item1Good {

    static final class GoodBoolean {
        static int created = 0;
        public static final GoodBoolean TRUE = new GoodBoolean(true);
        public static final GoodBoolean FALSE = new GoodBoolean(false);

        private final boolean value;

        private GoodBoolean(boolean value) {
            this.value = value;
            created++;
        }

        // 인스턴스 통제(instance-controlled): 세상에 이 클래스 인스턴스는 딱 2개뿐이다
        public static GoodBoolean valueOf(boolean b) {
            return b ? TRUE : FALSE;
        }

        public static GoodBoolean valueOf(String s) {
            return valueOf(Boolean.parseBoolean(s));
        }

        boolean booleanValue() {
            return value;
        }
    }
}
