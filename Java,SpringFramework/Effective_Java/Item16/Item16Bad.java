// ① 흔히 쓰는(나쁜) 코드 — 생성자에서 검증했으니 안전하다고 믿는다
// 대상 Java 버전: 1.0+ → Java 7 레거시에서도 그대로 컴파일된다
// 실행 진입점은 Item16Problem.java 다.

public class Item16Bad {

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
}
