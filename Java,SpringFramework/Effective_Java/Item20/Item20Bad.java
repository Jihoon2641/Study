// ① 흔히 쓰는(나쁜) 코드 — 새 기능(CSV 내보내기)을 추상 클래스 타입으로 정의했다
// 대상 Java 버전: 5+ → Java 7 레거시에서도 그대로 컴파일된다
// 실행 진입점은 Item20Problem.java 다.

public class Item20Bad {

    // 내보내기 타입을 추상 클래스로 정의했다. 기본 구현은 디버깅용 toString 을 그대로 쓴다
    static abstract class AbstractExportable {
        String csvRow() {
            return toString();
        }
    }

    // Order 는 이미 BaseEntity 를 상속하고 있다.
    // static final class Order extends BaseEntity, AbstractExportable { }
    // 컴파일 에러: '{' expected  ← 클래스는 하나만 상속할 수 있다
    // 그래서 AbstractExportable 을 끼워 넣을 자리가 공통 조상인 BaseEntity 위밖에 없었다
    static abstract class BaseEntity extends AbstractExportable {
        final long id;

        BaseEntity(long id) {
            this.id = id;
        }
    }

    static final class Order extends BaseEntity {
        final long amount;

        Order(long id, long amount) {
            super(id);
            this.amount = amount;
        }

        @Override
        String csvRow() {
            return id + "," + amount;   // 내보낼 컬럼만 고른다
        }
    }

    // 내보내기와 무관한 엔티티 — 하지만 BaseEntity 를 상속하는 순간 AbstractExportable 이 된다
    static final class User extends BaseEntity {
        final String email;
        final String passwordHash;

        User(long id, String email, String passwordHash) {
            super(id);
            this.email = email;
            this.passwordHash = passwordHash;
        }

        @Override
        public String toString() {   // 로그 디버깅용으로 모든 필드를 찍는다 (Lombok @ToString 과 같은 형태)
            return "User(id=" + id + ", email=" + email + ", passwordHash=" + passwordHash + ")";
        }
    }
}
