// ① 흔히 쓰는(나쁜) 코드 — "상수니까 public static final이면 되겠지"
// 대상 Java 버전: 1.0+ → Java 7 레거시에서도 그대로 컴파일된다
// 실행 진입점은 Item15Problem.java 다.

public class Item15Bad {

    // final은 '참조'만 고정한다. 배열 원소는 패키지 밖 누구든 바꿀 수 있다.
    public static final Item15Common.Thing[] VALUES = {new Item15Common.Thing("ONE"), new Item15Common.Thing("TWO")};
}
