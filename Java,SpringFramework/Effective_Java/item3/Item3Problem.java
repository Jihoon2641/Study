// ② 무엇이 문제인가 — ① Item3Bad 를 실제로 깨뜨리고, ③ Item3Good 과 나란히 비교한다
// 대상 Java 버전: 5+ 문법만 사용(enum, 리플렉션, 직렬화) → Java 7 레거시에서도 그대로 동작
// 실행 (Java 11~21): javac -d out/cli/Item3 item3/*.java && java -cp out/cli/Item3 Item3Problem
// 실행 (Java 22+):   java item3/Item3Problem.java   ← 같은 폴더의 다른 소스 파일을 자동으로 찾는다

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public class Item3Problem {

    public static void main(String[] args) throws Exception {
        System.out.println("=== ① Elvis: 리플렉션 ===");
        Constructor<Item3Bad.Elvis> ctor = Item3Bad.Elvis.class.getDeclaredConstructor();
        ctor.setAccessible(true);                             // 스프링 같은 리플렉션 기반 도구가 늘 하는 호출
        Item3Bad.Elvis byReflection = ctor.newInstance();
        System.out.println("byReflection == INSTANCE ? " + (byReflection == Item3Bad.Elvis.INSTANCE));
        // 출력: false  ← 컴파일 에러도 예외도 없이 두 번째 엘비스가 생겼다

        System.out.println("=== ① Elvis: 직렬화 왕복 (세션 복제·Redis 세션 저장을 흉내) ===");
        Item3Bad.Elvis fromSession = (Item3Bad.Elvis) roundTrip(Item3Bad.Elvis.INSTANCE);
        System.out.println("fromSession == INSTANCE ? " + (fromSession == Item3Bad.Elvis.INSTANCE));
        // 출력: false  ← 역직렬화할 때마다 새 엘비스. private 생성자는 호출조차 되지 않는다

        System.out.println();
        System.out.println("=== ③-1 GuardedElvis ===");
        try {
            Constructor<Item3Good.GuardedElvis> g = Item3Good.GuardedElvis.class.getDeclaredConstructor();
            g.setAccessible(true);
            g.newInstance();
        } catch (InvocationTargetException e) {               // 생성자 안에서 던진 예외는 이렇게 감싸져 나온다
            System.out.println("리플렉션 차단: " + e.getCause().getMessage());
            // 출력: 리플렉션 차단: 두 번째 GuardedElvis 생성 시도
        }
        System.out.println("역직렬화 결과 == INSTANCE ? "
                + (roundTrip(Item3Good.GuardedElvis.INSTANCE) == Item3Good.GuardedElvis.INSTANCE));
        // 출력: true  ← 단, 이 두 방어 코드 중 하나라도 빠뜨리면 ①로 돌아간다

        System.out.println();
        System.out.println("=== ③-2 EnumElvis ===");
        try {
            Constructor<?> e = Item3Good.EnumElvis.class.getDeclaredConstructors()[0];  // 컴파일러가 만든 (String name, int ordinal) 생성자
            e.setAccessible(true);
            e.newInstance("INSTANCE2", 1);
        } catch (IllegalArgumentException ex) {
            System.out.println("리플렉션 차단: " + ex.getMessage());
            // 출력: 리플렉션 차단: Cannot reflectively create enum objects
        }
        System.out.println("역직렬화 결과 == INSTANCE ? "
                + (roundTrip(Item3Good.EnumElvis.INSTANCE) == Item3Good.EnumElvis.INSTANCE));
        // 출력: true  ← 직렬화 명세가 열거 상수를 '이름'으로만 주고받게 정해 두었다

        // Item3Good.EnumElvis another = new Item3Good.EnumElvis();
        // 컴파일 에러: enum classes may not be instantiated
    }

    // 객체를 바이트로 썼다가 다시 읽는다 — 세션 클러스터링·분산 캐시가 내부에서 하는 일
    static Object roundTrip(Object o) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bytes);
        out.writeObject(o);
        out.close();
        return new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())).readObject();
    }
}
