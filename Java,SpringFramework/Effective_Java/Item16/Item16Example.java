// 대상 Java 버전: 7+ (문법상 Java 7 레거시에서도 그대로 컴파일된다 — Legacy7 클래스 불필요)
// 실행: java Item16/Item16Example.java  (단일 파일 소스 실행은 Java 11+)

public class Item16Example {

    // ① 흔히 쓰는(나쁜) 코드 — 생성자에서 검증했으니 안전하다고 믿는다
    static final class BadTime {
        public int hour;    // 필드가 public 이라 생성자 검증을 우회해 직접 대입할 수 있다
        public int minute;

        BadTime(int hour, int minute) {
            if (hour < 0 || hour >= 24) throw new IllegalArgumentException("시: " + hour);
            if (minute < 0 || minute >= 60) throw new IllegalArgumentException("분: " + minute);
            this.hour = hour;
            this.minute = minute;
        }
    }

    // ③ 개선된 코드 — 필드는 private, 접근은 메서드로만
    static final class Time {
        // 내부 표현을 "자정 이후 분" 하나로 바꿨다. 클라이언트는 이 사실을 모른다.
        private int minuteOfDay;

        Time(int hour, int minute) { set(hour, minute); }

        public int hour()   { return minuteOfDay / 60; }
        public int minute() { return minuteOfDay % 60; }

        public void set(int hour, int minute) {   // 모든 쓰기가 이 한 곳을 지난다
            if (hour < 0 || hour >= 24) throw new IllegalArgumentException("시: " + hour);
            if (minute < 0 || minute >= 60) throw new IllegalArgumentException("분: " + minute);
            this.minuteOfDay = hour * 60 + minute;
        }

        @Override public String toString() { return String.format("%02d:%02d", hour(), minute()); }
    }

    // 회의실 예약 시스템의 다른 모듈 — BadTime 을 만든 사람과 다른 사람이 짰다
    static boolean sameSlot(BadTime a, BadTime b) {
        return a.hour * 60 + a.minute == b.hour * 60 + b.minute;
    }

    // ② 무엇이 문제인가 — 말로 때우지 말고 여기서 실제로 깨뜨린다
    public static void main(String[] args) {
        System.out.println("=== ① BadTime: 생성자 검증은 통과, 그 다음이 문제 ===");
        try {
            new BadTime(10, 75);
        } catch (IllegalArgumentException e) {
            System.out.println("new BadTime(10, 75) → 차단됨: " + e.getMessage());
        }
        // 출력: new BadTime(10, 75) → 차단됨: 분: 75

        BadTime meetingA = new BadTime(10, 30);
        BadTime meetingB = new BadTime(11, 15);

        // "15분 연장" 기능을 구현한 누군가가 분만 더하고 시 올림을 빠뜨렸다.
        // 컴파일 에러 없음. 런타임 예외 없음. 로그 한 줄 안 남는다.
        meetingA.minute += 45;

        System.out.println("연장 후 meetingA = " + meetingA.hour + ":" + meetingA.minute);
        // 출력: 연장 후 meetingA = 10:75  ← 존재할 수 없는 시각이 객체 안에 살아 있다
        System.out.println("meetingA 와 11:15 회의가 같은 슬롯인가? " + sameSlot(meetingA, meetingB));
        // 출력: true  ← 원인(연장 코드)과 증상(예약 중복 판정)이 서로 다른 모듈에서 드러난다

        System.out.println();
        System.out.println("=== ③ Time: 모든 쓰기가 검증을 지난다 ===");
        Time t = new Time(10, 30);
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
