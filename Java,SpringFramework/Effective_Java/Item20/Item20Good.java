// ③ 개선된 코드 — 새 기능을 인터페이스 타입으로 정의하고, 필요한 클래스만 구현한다
// 대상 Java 버전: 5+ → Java 7 레거시에서도 그대로 컴파일된다
// 실행 진입점은 Item20Problem.java 다.

public class Item20Good {

    // 믹스인 타입 — 기존 계층 어디에든 "덧붙일" 수 있다
    interface Exportable {
        String csvRow();
    }

    // 기존 계층은 그대로 둔다
    static abstract class BaseEntity {
        final long id;

        BaseEntity(long id) {
            this.id = id;
        }
    }

    // 이미 BaseEntity 를 상속한 클래스에도 implements 한 줄로 새 타입을 붙인다
    static final class Order extends BaseEntity implements Exportable {
        final long amount;

        Order(long id, long amount) {
            super(id);
            this.amount = amount;
        }

        @Override
        public String csvRow() {
            return id + "," + amount;
        }
    }

    // 내보내기와 무관한 엔티티는 Exportable 이 아니다
    static final class User extends BaseEntity {
        final String email;
        final String passwordHash;

        User(long id, String email, String passwordHash) {
            super(id);
            this.email = email;
            this.passwordHash = passwordHash;
        }

        @Override
        public String toString() {
            return "User(id=" + id + ", email=" + email + ", passwordHash=" + passwordHash + ")";
        }
    }
}
