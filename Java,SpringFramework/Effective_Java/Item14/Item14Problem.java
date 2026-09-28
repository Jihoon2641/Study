// ② 무엇이 문제인가 — ① Item14Bad 를 실제로 깨뜨리고, ③ Item14Good·Item14Legacy7 과 나란히 비교한다
// 대상 Java 버전: 7+ (이 파일 자체는 Java 7 문법. Item14Good 이 8+ 라서 폴더 전체는 8+ 로 컴파일한다)
// 실행 (Java 11~21): javac -d out/cli/Item14 Item14/*.java && java -cp out/cli/Item14 Item14Problem
// 실행 (Java 22+):   java Item14/Item14Problem.java   ← 같은 폴더의 다른 소스 파일을 자동으로 찾는다
// (Java 17 이하에서 한글 주석/출력이 깨지면 javac 에 -encoding UTF-8 을 붙인다)

import java.util.TreeSet;

public class Item14Problem {

    public static void main(String[] args) {
        Item14Bad.BadNode big = new Item14Bad.BadNode("BIG", Integer.MAX_VALUE);
        Item14Bad.BadNode neg = new Item14Bad.BadNode("NEG", -1);
        Item14Bad.BadNode zero = new Item14Bad.BadNode("ZERO", 0);

        // (1) 대칭성 위반 — 서로가 서로에게 "내가 더 작다"고 말한다
        System.out.println("BIG.compareTo(NEG) = " + big.compareTo(neg)); // 출력: -2147483648 (음수)
        System.out.println("NEG.compareTo(BIG) = " + neg.compareTo(big)); // 출력: -2147483648 (음수)
        // 2147483647 - (-1) 은 2147483648 이어야 하지만 int를 넘어 MIN_VALUE로 감긴다

        // (2) 그 결과 TreeSet의 순서가 통째로 무너진다. 예외는 안 난다 — 조용히 틀린 답만 나온다
        TreeSet<Item14Bad.BadNode> bad = new TreeSet<>();
        bad.add(neg);
        bad.add(zero);
        bad.add(big);                                               // 이 삽입 순서에서 BIG이 NEG의 왼쪽에 박힌다
        System.out.println("bad          = " + bad);              // 출력: [BIG(2147483647), NEG(-1), ZERO(0)]
        System.out.println("bad.first()  = " + bad.first());      // 출력: BIG(2147483647)  ← 최솟값이 MAX_VALUE
        System.out.println("ZERO 미만    = " + bad.headSet(zero)); // 출력: [BIG(2147483647), NEG(-1)]

        // (3) Integer.compare로 바꾸면 순서가 제자리를 찾는다
        TreeSet<Item14Good.GoodNode> good = new TreeSet<>();
        good.add(new Item14Good.GoodNode("NEG", -1));
        good.add(new Item14Good.GoodNode("ZERO", 0));
        good.add(new Item14Good.GoodNode("BIG", Integer.MAX_VALUE));
        System.out.println("good         = " + good);             // 출력: [NEG(-1), ZERO(0), BIG(2147483647)]
        System.out.println("good.first() = " + good.first());     // 출력: NEG(-1)

        // (4) Java 7 대안도 결과가 같다
        TreeSet<Item14Legacy7.LegacyNode> legacy = new TreeSet<>();
        legacy.add(new Item14Legacy7.LegacyNode("NEG", -1));
        legacy.add(new Item14Legacy7.LegacyNode("ZERO", 0));
        legacy.add(new Item14Legacy7.LegacyNode("BIG", Integer.MAX_VALUE));
        System.out.println("legacy7      = " + legacy);           // 출력: [NEG(-1), ZERO(0), BIG(2147483647)]
    }
}
