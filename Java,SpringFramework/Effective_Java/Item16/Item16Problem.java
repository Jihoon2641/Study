// ② 무엇이 문제인가 — ① Item16Bad 를 실제로 깨뜨리고, ③ Item16Good 과 나란히 비교한다
// 대상 Java 버전: 5+ → Java 7 레거시에서도 그대로 컴파일된다
// 실행 (Java 11~21): javac -d out/cli/Item16 Item16/*.java && java -cp out/cli/Item16 Item16Problem
// 실행 (Java 22+):   java Item16/Item16Problem.java   ← 같은 폴더의 다른 소스 파일을 자동으로 찾는다

public class Item16Problem {

    // 회의실 예약 시스템의 다른 모듈 — BadTime 을 만든 사람과 다른 사람이 짰다
    static boolean sameSlot(Item16Bad.BadTime a, Item16Bad.BadTime b) {
        return a.hour * 60 + a.minute == b.hour * 60 + b.minute;
    }

    public static void main(String[] args) {
        System.out.println("=== ① BadTime: 생성자 검증은 통과, 그 다음이 문제 ===");
        try {
            new Item16Bad.BadTime(10, 75);
        } catch (IllegalArgumentException e) {
            System.out.println("new BadTime(10, 75) → 차단됨: " + e.getMessage());
        }
        // 출력: new BadTime(10, 75) → 차단됨: 분: 75

        Item16Bad.BadTime meetingA = new Item16Bad.BadTime(10, 30);
        Item16Bad.BadTime meetingB = new Item16Bad.BadTime(11, 15);

        // "15분 연장" 기능을 구현한 누군가가 분만 더하고 시 올림을 빠뜨렸다.
        // 컴파일 에러 없음. 런타임 예외 없음. 로그 한 줄 안 남는다.
        meetingA.minute += 45;

        System.out.println("연장 후 meetingA = " + meetingA.hour + ":" + meetingA.minute);
        // 출력: 연장 후 meetingA = 10:75  ← 존재할 수 없는 시각이 객체 안에 살아 있다
        System.out.println("meetingA 와 11:15 회의가 같은 슬롯인가? " + sameSlot(meetingA, meetingB));
        // 출력: true  ← 원인(연장 코드)과 증상(예약 중복 판정)이 서로 다른 모듈에서 드러난다

        System.out.println();
        System.out.println("=== ③ Time: 모든 쓰기가 검증을 지난다 ===");
        Item16Good.Time t = new Item16Good.Time(10, 30);
        try {
            t.set(t.hour(), t.minute() + 45);   // 같은 실수를 해도
        } catch (IllegalArgumentException e) {
            System.out.println("t.set(10, 75) → 차단됨: " + e.getMessage());
        }
        // 출력: t.set(10, 75) → 차단됨: 분: 75  ← 실수한 바로 그 줄에서 터진다
        System.out.println("t = " + t);
        // 출력: t = 10:30  ← 불변식이 깨진 상태는 한 순간도 존재하지 않는다

        // t.minuteOfDay 를 쓰는 외부 코드는 없다 — 내부 표현을 (hour, minute)에서
        // minuteOfDay 로 바꿔도 hour()/minute() 를 호출하는 클라이언트는 한 줄도 안 고친다.
        // BadTime 이었다면 필드 hour/minute 를 없애는 순간 모든 사용처가 컴파일 에러다.
    }
}
