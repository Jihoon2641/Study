// 대상 Java 버전: 5+ 문법만 사용(enum, 리플렉션, 직렬화) → Java 7 레거시에서도 그대로 동작. Legacy7 불필요.
// 실행: java Item3/Item3Example.java  (단일 파일 소스 실행은 Java 11+)

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public class Item3Example {

    // ① 흔히 쓰는(나쁜) 코드 — public static final 필드 싱글턴에 "세션에 담아야 하니" Serializable 만 붙였다
    static class Elvis implements Serializable {
        public static final Elvis INSTANCE = new Elvis();
        private Elvis() { }                                   // private 이면 하나뿐이라고 믿는다
    }

    // ③-1 클래스 방식을 유지해야 할 때 — 생성자 가드(리플렉션 방어) + readResolve(직렬화 방어)
    static class GuardedElvis implements Serializable {
        public static final GuardedElvis INSTANCE = new GuardedElvis();
        private GuardedElvis() {
            if (INSTANCE != null) throw new IllegalStateException("두 번째 GuardedElvis 생성 시도");
        }
        private Object readResolve() { return INSTANCE; }    // 역직렬화로 생긴 객체는 버리고 진짜를 돌려준다
    }

    // ③-2 책이 권하는 방식 — 원소 하나짜리 열거 타입. 방어 코드가 한 줄도 없다.
    enum EnumElvis { INSTANCE }

    // ② 무엇이 문제인가
    public static void main(String[] args) throws Exception {
        System.out.println("=== ① Elvis: 리플렉션 ===");
        Constructor<Elvis> ctor = Elvis.class.getDeclaredConstructor();
        ctor.setAccessible(true);                             // 스프링 같은 리플렉션 기반 도구가 늘 하는 호출
        Elvis byReflection = ctor.newInstance();
        System.out.println("byReflection == INSTANCE ? " + (byReflection == Elvis.INSTANCE));
        // 출력: false  ← 컴파일 에러도 예외도 없이 두 번째 엘비스가 생겼다

        System.out.println("=== ① Elvis: 직렬화 왕복 (세션 복제·Redis 세션 저장을 흉내) ===");
        Elvis fromSession = (Elvis) roundTrip(Elvis.INSTANCE);
        System.out.println("fromSession == INSTANCE ? " + (fromSession == Elvis.INSTANCE));
        // 출력: false  ← 역직렬화할 때마다 새 엘비스. private 생성자는 호출조차 되지 않는다

        System.out.println();
        System.out.println("=== ③-1 GuardedElvis ===");
        try {
            Constructor<GuardedElvis> g = GuardedElvis.class.getDeclaredConstructor();
            g.setAccessible(true);
            g.newInstance();
        } catch (InvocationTargetException e) {               // 생성자 안에서 던진 예외는 이렇게 감싸져 나온다
            System.out.println("리플렉션 차단: " + e.getCause().getMessage());
            // 출력: 리플렉션 차단: 두 번째 GuardedElvis 생성 시도
        }
        System.out.println("역직렬화 결과 == INSTANCE ? " + (roundTrip(GuardedElvis.INSTANCE) == GuardedElvis.INSTANCE));
        // 출력: true  ← 단, 이 두 방어 코드 중 하나라도 빠뜨리면 ①로 돌아간다

        System.out.println();
        System.out.println("=== ③-2 EnumElvis ===");
        try {
            Constructor<?> e = EnumElvis.class.getDeclaredConstructors()[0];  // 컴파일러가 만든 (String name, int ordinal) 생성자
            e.setAccessible(true);
            e.newInstance("INSTANCE2", 1);
        } catch (IllegalArgumentException ex) {
            System.out.println("리플렉션 차단: " + ex.getMessage());
            // 출력: 리플렉션 차단: Cannot reflectively create enum objects
        }
        System.out.println("역직렬화 결과 == INSTANCE ? " + (roundTrip(EnumElvis.INSTANCE) == EnumElvis.INSTANCE));
        // 출력: true  ← 직렬화 명세가 열거 상수를 '이름'으로만 주고받게 정해 두었다

        // EnumElvis another = new EnumElvis();
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
