// ① 흔히 쓰는(나쁜) 코드 — ①-1 점층적 생성자 패턴, ①-2 자바빈즈 패턴
// 대상 Java 버전: 5+ 문법만 사용 → Java 7 레거시에서도 그대로 컴파일된다
// 실행 진입점은 Item2Problem.java 다.

public class Item2Bad {

    // ①-1 점층적 생성자 패턴(telescoping constructor) — 선택 매개변수가 늘 때마다 생성자를 덧붙인다
    static final class TelescopingNutritionFacts {
        private final int servingSize, servings, calories, fat, sodium;

        public TelescopingNutritionFacts(int servingSize, int servings) {
            this(servingSize, servings, 0);
        }

        public TelescopingNutritionFacts(int servingSize, int servings, int calories) {
            this(servingSize, servings, calories, 0);
        }

        public TelescopingNutritionFacts(int servingSize, int servings, int calories, int fat) {
            this(servingSize, servings, calories, fat, 0);
        }

        public TelescopingNutritionFacts(int servingSize, int servings, int calories, int fat, int sodium) {
            this.servingSize = servingSize;
            this.servings = servings;
            this.calories = calories;
            this.fat = fat;
            this.sodium = sodium;
        }

        @Override
        public String toString() {
            return "칼로리 " + calories + "kcal, 지방 " + fat + "g, 나트륨 " + sodium + "mg";
        }
    }

    // ①-2 자바빈즈 패턴(JavaBeans) — 기본 생성자로 만들고 세터로 채운다
    static final class JavaBeansNutritionFacts {
        private int servingSize = -1;   // 필수; 기본값 없음
        private int servings = -1;      // 필수; 기본값 없음
        private int calories = 0;
        // private final int servings;  → 컴파일 에러: variable servings not initialized in the default constructor
        //                                 (세터로 채우는 구조라 final 불가 → 불변 객체로 만들 길이 없다)

        public void setServingSize(int val) {
            servingSize = val;
        }

        public void setServings(int val) {
            servings = val;
        }

        public void setCalories(int val) {
            calories = val;
        }

        int totalMl() {
            return servingSize * servings;
        }
    }
}
