// ③ 개선된 코드 — 필드는 private, 접근은 메서드로만
// 대상 Java 버전: 5+ (String.format) → Java 7 레거시에서도 그대로 컴파일된다
// 실행 진입점은 Item16Problem.java 다.

public class Item16Good {

    static final class Time {
        // 내부 표현을 "자정 이후 분" 하나로 바꿨다. 클라이언트는 이 사실을 모른다.
        private int minuteOfDay;

        Time(int hour, int minute) {
            set(hour, minute);
        }

        public int hour() {
            return minuteOfDay / 60;
        }

        public int minute() {
            return minuteOfDay % 60;
        }

        public void set(int hour, int minute) {   // 모든 쓰기가 이 한 곳을 지난다
            if (hour < 0 || hour >= 24) throw new IllegalArgumentException("시: " + hour);
            if (minute < 0 || minute >= 60) throw new IllegalArgumentException("분: " + minute);
            this.minuteOfDay = hour * 60 + minute;
        }

        @Override
        public String toString() {
            return String.format("%02d:%02d", hour(), minute());
        }
    }
}
