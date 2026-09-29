// ② 무엇이 문제인가 — ① Item19Bad 를 실제로 깨뜨리고, ③ Item19Good 과 나란히 비교한다
// 대상 Java 버전: 8+ (java.time.Instant). NPE 메시지의 상세 설명은 Java 14+ (JEP 358, 15+ 기본 활성)
// 실행 (Java 11~21): javac -d out/cli/Item19 Item19/*.java && java -cp out/cli/Item19 Item19Problem
// 실행 (Java 22+):   java Item19/Item19Problem.java   ← 같은 폴더의 다른 소스 파일을 자동으로 찾는다

public class Item19Problem {

    public static void main(String[] args) {
        System.out.println("=== ① Bad: new Sub() 한 번에 overrideMe 가 두 번 불린다 ===");
        Item19Bad.Sub sub = new Item19Bad.Sub();
        // 출력: overrideMe: instant = null  ← Super() 안에서 불렸다. Sub() 본문은 아직 실행 전이다
        sub.overrideMe();
        // 출력: overrideMe: instant = 2026-...Z (현재 시각)
        //       ← 같은 final 필드가 null 과 실제 값, 두 상태로 관찰됐다. 컴파일 에러도 예외도 없다

        System.out.println();
        System.out.println("=== ① Bad: 필드를 역참조하는 하위 클래스는 생성조차 안 된다 ===");
        try {
            new Item19Bad.StrictSub();
        } catch (NullPointerException e) {
            System.out.println("new StrictSub() → NPE: " + e.getMessage());
            // 출력: new StrictSub() → NPE: Cannot invoke "java.time.Instant.getEpochSecond()"
            //       because "this.instant" is null
            // ← 스택 트레이스는 Super.<init> → StrictSub.overrideMe 를 가리킨다.
            //   StrictSub 작성자는 overrideMe 를 호출한 적이 없는데 그 안에서 터진다
        }

        System.out.println();
        System.out.println("=== ③ Good: 생성자는 private 도우미만 호출한다 ===");
        Item19Good.Sub goodSub = new Item19Good.Sub();
        // 출력: (없음)  ← 생성 중에 하위 클래스 코드가 실행되지 않는다
        goodSub.overrideMe();
        // 출력: overrideMe: instant = 2026-...Z (현재 시각)  ← 딱 한 번, 초기화가 끝난 뒤에
        new Item19Good.StrictSub().overrideMe();
        // 출력: overrideMe: epochSecond = 17...  ← 같은 하위 클래스 코드가 이제는 정상 동작한다
    }
}
