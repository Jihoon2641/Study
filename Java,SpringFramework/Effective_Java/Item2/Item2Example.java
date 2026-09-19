// 대상 Java 버전: 5+ 문법만 사용 → Java 7 레거시에서도 그대로 컴파일된다(Legacy7 불필요).
// 실행: java Item2/Item2Example.java  (단일 파일 소스 실행은 Java 11+)

public class Item2Example {

    // ①-1 점층적 생성자 패턴(telescoping constructor) — 선택 매개변수가 늘 때마다 생성자를 덧붙인다
    static final class TelescopingNutritionFacts {
        private final int servingSize, servings, calories, fat, sodium;

        public TelescopingNutritionFacts(int servingSize, int servings) { this(servingSize, servings, 0); }
        public TelescopingNutritionFacts(int servingSize, int servings, int calories) { this(servingSize, servings, calories, 0); }
        public TelescopingNutritionFacts(int servingSize, int servings, int calories, int fat) { this(servingSize, servings, calories, fat, 0); }
        public TelescopingNutritionFacts(int servingSize, int servings, int calories, int fat, int sodium) {
            this.servingSize = servingSize; this.servings = servings;
            this.calories = calories; this.fat = fat; this.sodium = sodium;
        }
        @Override public String toString() { return "칼로리 " + calories + "kcal, 지방 " + fat + "g, 나트륨 " + sodium + "mg"; }
    }

    // ①-2 자바빈즈 패턴(JavaBeans) — 기본 생성자로 만들고 세터로 채운다
    static final class JavaBeansNutritionFacts {
        private int servingSize = -1;   // 필수; 기본값 없음
        private int servings    = -1;   // 필수; 기본값 없음
        private int calories    = 0;
        // private final int servings;  → 컴파일 에러: variable servings not initialized in the default constructor
        //                                 (세터로 채우는 구조라 final 불가 → 불변 객체로 만들 길이 없다)

        public void setServingSize(int val) { servingSize = val; }
        public void setServings(int val)    { servings = val; }
        public void setCalories(int val)    { calories = val; }
        int totalMl() { return servingSize * servings; }
    }

    // ③ 빌더 패턴(Builder) — 필수는 빌더 생성자로, 선택은 이름 있는 메서드로, 완성은 build() 한 번에
    static final class NutritionFacts {
        private final int servingSize, servings, calories, fat, sodium;

        public static class Builder {
            private final int servingSize, servings;             // 필수 매개변수
            private int calories = 0, fat = 0, sodium = 0;       // 선택 매개변수 — 기본값으로 초기화

            public Builder(int servingSize, int servings) { this.servingSize = servingSize; this.servings = servings; }
            public Builder calories(int val) { calories = val; return this; }
            public Builder fat(int val)      { fat = val;      return this; }
            public Builder sodium(int val)   { sodium = val;   return this; }
            public NutritionFacts build()    { return new NutritionFacts(this); }
        }

        private NutritionFacts(Builder b) {
            servingSize = b.servingSize; servings = b.servings;
            calories = b.calories; fat = b.fat; sodium = b.sodium;
            // 여러 필드에 걸친 불변식은 '복사를 마친 필드'로 검사한다 → 잘못된 객체는 세상에 나오지 못한다
            if (servingSize <= 0 || servings <= 0)
                throw new IllegalArgumentException("servingSize, servings 는 양수여야 함: " + servingSize + ", " + servings);
        }
        @Override public String toString() { return "칼로리 " + calories + "kcal, 지방 " + fat + "g, 나트륨 " + sodium + "mg"; }
    }

    // ② 무엇이 문제인가
    public static void main(String[] args) {
        System.out.println("=== ①-1 점층적 생성자: 같은 int 두 개의 순서를 바꿔 씀 ===");
        // 의도: 칼로리 100, 지방 0g, 나트륨 35mg. (…, fat, sodium) 순서를 착각해 35와 0을 뒤집었다.
        TelescopingNutritionFacts cola = new TelescopingNutritionFacts(240, 8, 100, 35, 0);
        System.out.println("라벨 = " + cola);
        // 출력: 칼로리 100kcal, 지방 35g, 나트륨 0mg  ← 컴파일 OK, 예외 없음. 잘못된 라벨이 그대로 인쇄된다

        System.out.println();
        System.out.println("=== ①-2 자바빈즈: 세터 하나 빠뜨림 ===");
        JavaBeansNutritionFacts beans = new JavaBeansNutritionFacts();
        beans.setServingSize(240);
        beans.setCalories(100);
        // beans.setServings(8);   ← 필드가 추가된 뒤 이 호출 한 줄이 누락됐다. 컴파일러는 모른다.
        System.out.println("총 용량 = " + beans.totalMl() + "ml");
        // 출력: 총 용량 = -240ml  ← 생성은 '성공'했고, 한참 뒤 계산 결과에서야 드러난다

        System.out.println();
        System.out.println("=== ③ 빌더 ===");
        NutritionFacts good = new NutritionFacts.Builder(240, 8).calories(100).sodium(35).build();
        System.out.println("라벨 = " + good);
        // 출력: 칼로리 100kcal, 지방 0g, 나트륨 35mg  ← 이름으로 넣으니 순서를 뒤집을 수가 없다

        // NutritionFacts noServings = new NutritionFacts.Builder(240).calories(100).build();
        // 컴파일 에러: constructor Builder in class Builder cannot be applied to given types
        //            (필수 값 누락이 실행 전에 막힌다)

        try {
            new NutritionFacts.Builder(240, 0).calories(100).build();
        } catch (IllegalArgumentException e) {
            System.out.println("build() 시점에 차단: " + e.getMessage());
            // 출력: build() 시점에 차단: servingSize, servings 는 양수여야 함: 240, 0
        }
    }
}
