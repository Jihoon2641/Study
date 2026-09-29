// ② 무엇이 문제인가 — ① Item20Bad 를 실제로 깨뜨리고, ③ Item20Good 과 나란히 비교한다
// 대상 Java 버전: 7+ (다이아몬드 연산자) → Java 7 레거시에서도 그대로 컴파일된다
// 실행 (Java 11~21): javac -d out/cli/Item20 Item20/*.java && java -cp out/cli/Item20 Item20Problem
// 실행 (Java 22+):   java Item20/Item20Problem.java   ← 같은 폴더의 다른 소스 파일을 자동으로 찾는다

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Item20Problem {

    // 야간 배치 — "내보낼 수 있는 엔티티는 전부 CSV 로 외부 정산 시스템에 보낸다"
    static List<String> exportBad(List<Object> allEntities) {
        List<String> rows = new ArrayList<>();
        for (Object e : allEntities)
            if (e instanceof Item20Bad.AbstractExportable)
                rows.add(((Item20Bad.AbstractExportable) e).csvRow());
        return rows;
    }

    static List<String> exportGood(List<Object> allEntities) {
        List<String> rows = new ArrayList<>();
        for (Object e : allEntities)
            if (e instanceof Item20Good.Exportable)
                rows.add(((Item20Good.Exportable) e).csvRow());
        return rows;
    }

    // 사용자 관리 화면 개발자가 "목록 다운로드" 기능에 이 메서드를 재사용하려 한다
    static void download(List<? extends Item20Good.Exportable> rows) {
    }

    public static void main(String[] args) {
        System.out.println("=== ① 추상 클래스 타입: 공통 조상에 끼워 넣었다 ===");
        List<Object> bad = Arrays.<Object>asList(
                new Item20Bad.Order(1, 30_000),
                new Item20Bad.User(2, "kim@example.com", "$2a$10$Xyz..."));
        for (String row : exportBad(bad)) System.out.println(row);
        // 출력: 1,30000
        //       User(id=2, email=kim@example.com, passwordHash=$2a$10$Xyz...)
        // ← User 는 내보내기 대상이 아닌데 BaseEntity 를 상속해서 AbstractExportable 이 됐다.
        //   컴파일 에러 없음. 런타임 예외 없음. 비밀번호 해시가 외부 시스템으로 나갔다

        System.out.println();
        System.out.println("=== ③ 인터페이스 타입: 필요한 클래스에만 붙였다 ===");
        List<Object> good = Arrays.<Object>asList(
                new Item20Good.Order(1, 30_000),
                new Item20Good.User(2, "kim@example.com", "$2a$10$Xyz..."));
        for (String row : exportGood(good)) System.out.println(row);
        // 출력: 1,30000  ← User 는 Exportable 이 아니므로 빠진다

        List<Item20Good.User> users = new ArrayList<>();
        // download(users);
        // 컴파일 에러: incompatible types: List<User> cannot be converted to List<? extends Exportable>
        // ← 같은 실수가 운영이 아니라 컴파일 시점에 막힌다
    }
}
