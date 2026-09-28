// ① 흔히 쓰는(나쁜) 코드 — public static final 필드 싱글턴에 "세션에 담아야 하니" Serializable 만 붙였다
// 대상 Java 버전: 5+ 문법만 사용 → Java 7 레거시에서도 그대로 동작
// 실행 진입점은 Item3Problem.java 다.

import java.io.Serializable;

public class Item3Bad {

    static class Elvis implements Serializable {
        public static final Elvis INSTANCE = new Elvis();

        private Elvis() {
            // private 이면 하나뿐이라고 믿는다
        }
    }
}
