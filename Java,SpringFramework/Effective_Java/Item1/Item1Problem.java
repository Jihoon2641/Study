// ② 무엇이 문제인가 — ① Item1Bad 를 실제로 깨뜨리고, ③ Item1Good 과 나란히 비교한다
// 대상 Java 버전: 7+ (숫자 리터럴의 밑줄 1_000_000 이 Java 7 문법)
// 실행 (Java 11~21): javac -d out/cli/Item1 Item1/*.java && java -cp out/cli/Item1 Item1Problem
// 실행 (Java 22+):   java Item1/Item1Problem.java   ← 같은 폴더의 다른 소스 파일을 자동으로 찾는다

public class Item1Problem {

    static final int ROWS = 1_000_000;   // 가상의 CSV 100만 행, "active" 컬럼 값은 "true"/"false" 두 종류뿐

    public static void main(String[] args) {
        System.out.println("=== ① Bad: 행마다 new BadBoolean(...) ===");
        Item1Bad.BadBoolean[] badRows = new Item1Bad.BadBoolean[ROWS];   // 파싱 결과를 들고 있는 상황(전부 힙에 생존)
        for (int i = 0; i < ROWS; i++) {
            badRows[i] = new Item1Bad.BadBoolean(Boolean.parseBoolean(i % 2 == 0 ? "true" : "false"));
        }
        System.out.println("생성된 객체 수 = " + Item1Bad.BadBoolean.created);
        // 출력: 1000002  ← 값은 두 종류인데 객체는 100만 개 + 상수 2개

        // "상수가 있으니 == 로 비교해도 되겠지" — 컴파일도 되고 예외도 없다
        Item1Bad.BadBoolean active = badRows[0];                         // 값은 분명히 true
        System.out.println("active.booleanValue()     = " + active.booleanValue());
        System.out.println("active == BadBoolean.TRUE = " + (active == Item1Bad.BadBoolean.TRUE));
        // 출력: true / false  ← '활성 사용자' 분기를 아무 에러 없이 조용히 건너뛴다

        System.out.println();
        System.out.println("=== ③ Good: 행마다 GoodBoolean.valueOf(...) ===");
        Item1Good.GoodBoolean[] goodRows = new Item1Good.GoodBoolean[ROWS];
        for (int i = 0; i < ROWS; i++) {
            goodRows[i] = Item1Good.GoodBoolean.valueOf(i % 2 == 0 ? "true" : "false");
        }
        System.out.println("생성된 객체 수 = " + Item1Good.GoodBoolean.created);
        // 출력: 2  ← 배열 100만 칸이 같은 두 객체를 가리킬 뿐이다
        System.out.println("goodRows[0] == GoodBoolean.TRUE = " + (goodRows[0] == Item1Good.GoodBoolean.TRUE));
        // 출력: true  ← 인스턴스가 2개뿐임을 클래스가 보장하므로 == 가 equals 와 같은 뜻이 된다

        // Item1Good.GoodBoolean leak = new Item1Good.GoodBoolean(true);
        // 컴파일 에러: GoodBoolean(boolean) has private access in GoodBoolean
        // → 다른 톱레벨 클래스에서는 정적 팩터리를 거치지 않고 인스턴스를 만들 방법이 없다

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
