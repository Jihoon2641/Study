// Java 7 대안 — List.of가 없으니 배열을 private으로 감추고 불변 뷰만 공개한다
// 대상 Java 버전: 1.2+ (Collections.unmodifiableList) → Java 7 레거시에서 그대로 사용
// 실행 진입점은 Item15Problem.java 다.

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Item15Legacy7 {

    private static final Item15Common.Thing[] PRIVATE_VALUES =
            {new Item15Common.Thing("ONE"), new Item15Common.Thing("TWO")};

    public static final List<Item15Common.Thing> VALUES =
            Collections.unmodifiableList(Arrays.asList(PRIVATE_VALUES));
}
