 // 대상 Java 버전: 7+ (숫자 리터럴의 밑줄 1_000_000 이 Java 7 문법). Java 7 전용 문법이 없어 Legacy7 불필요.
// 실행: java Item1/Item1Example.java  (단일 파일 소스 실행은 Java 11+)

public class Item1Example {

    // ① 흔히 쓰는(나쁜) 코드 — 책의 Boolean 을 흉내 내되, public 생성자만 열어 두었다
    static final class BadBoolean {
        static int created = 0;                               // 생성 횟수 측정용
        public static final BadBoolean TRUE  = new BadBoolean(true);
        public static final BadBoolean FALSE = new BadBoolean(false);

        private final boolean value;
        public BadBoolean(boolean value) { this.value = value; created++; }  // 호출할 때마다 무조건 새 객체
        boolean booleanValue() { return value; }
    }

    // ③ 개선된 코드 — 생성자를 막고, 정적 팩터리 메서드로만 인스턴스를 내준다 (책의 Boolean.valueOf)
    static final class GoodBoolean {
        static int created = 0;
        public static final GoodBoolean TRUE  = new GoodBoolean(true);
        public static final GoodBoolean FALSE = new GoodBoolean(false);

        private final boolean value;
        private GoodBoolean(boolean value) { this.value = value; created++; }

        // 인스턴스 통제(instance-controlled): 세상에 이 클래스 인스턴스는 딱 2개뿐이다
        public static GoodBoolean valueOf(boolean b) { return b ? TRUE : FALSE; }
        public static GoodBoolean valueOf(String s)  { return valueOf(Boolean.parseBoolean(s)); }
        boolean booleanValue() { return value; }
    }

    static final int ROWS = 1_000_000;   // 가상의 CSV 100만 행, "active" 컬럼 값은 "true"/"false" 두 종류뿐

    // ② 무엇이 문제인가 — 여기서 실제로 깨뜨린다
    public static void main(String[] args) {
        System.out.println("=== ① Bad: 행마다 new BadBoolean(...) ===");
        BadBoolean[] badRows = new BadBoolean[ROWS];          // 파싱 결과를 들고 있는 상황(전부 힙에 생존)
        for (int i = 0; i < ROWS; i++) {
            badRows[i] = new BadBoolean(Boolean.parseBoolean(i % 2 == 0 ? "true" : "false"));
        }
        System.out.println("생성된 객체 수 = " + BadBoolean.created);
        // 출력: 1000002  ← 값은 두 종류인데 객체는 100만 개 + 상수 2개

        // "상수가 있으니 == 로 비교해도 되겠지" — 컴파일도 되고 예외도 없다
        BadBoolean active = badRows[0];                       // 값은 분명히 true
        System.out.println("active.booleanValue()     = " + active.booleanValue());
        System.out.println("active == BadBoolean.TRUE = " + (active == BadBoolean.TRUE));
        // 출력: true / false  ← '활성 사용자' 분기를 아무 에러 없이 조용히 건너뛴다

        System.out.println();
        System.out.println("=== ③ Good: 행마다 GoodBoolean.valueOf(...) ===");
        GoodBoolean[] goodRows = new GoodBoolean[ROWS];
        for (int i = 0; i < ROWS; i++) {
            goodRows[i] = GoodBoolean.valueOf(i % 2 == 0 ? "true" : "false");
        }
        System.out.println("생성된 객체 수 = " + GoodBoolean.created);
        // 출력: 2  ← 배열 100만 칸이 같은 두 객체를 가리킬 뿐이다
        System.out.println("goodRows[0] == GoodBoolean.TRUE = " + (goodRows[0] == GoodBoolean.TRUE));
        // 출력: true  ← 인스턴스가 2개뿐임을 클래스가 보장하므로 == 가 equals 와 같은 뜻이 된다

        // 주의: 아래 줄은 '이 파일 안에서는' 컴파일된다. 중첩 클래스의 private 은 바깥 클래스(같은 nest)에 열려 있기 때문.
        // GoodBoolean leak = new GoodBoolean(true);
        // 다른 톱레벨 클래스에서 쓰면 → 컴파일 에러: GoodBoolean(boolean) has private access in Item1Example.GoodBoolean

        System.out.println();
        System.out.println("=== 함정: JDK 정적 팩터리의 캐시는 '보장된 범위'까지만 믿어라 ===");
        Integer a = Integer.valueOf(127), b = Integer.valueOf(127);
        Integer c = Integer.valueOf(128), d = Integer.valueOf(128);
        System.out.println("valueOf(127) == valueOf(127) → " + (a == b));
        // 출력: true   ← -128~127 은 캐시가 명세로 보장된다
        System.out.println("valueOf(128) == valueOf(128) → " + (c == d));
        // 출력: false  ← 범위 밖은 새 객체 (기본 설정 기준. -XX:AutoBoxCacheMax 로 범위를 넓히면 true 가 될 수도 있다)
        System.out.println("valueOf(128).equals(valueOf(128)) → " + c.equals(d));
        // 출력: true   ← 테스트 데이터가 100 이하라 통과하던 == 비교가, 운영에서 주문 수량 128 이 들어오는 순간 깨진다
    }
}
