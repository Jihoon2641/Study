// ③ 개선된 코드 — 빌더 패턴(Builder): 필수는 빌더 생성자로, 선택은 이름 있는 메서드로, 완성은 build() 한 번에
// 대상 Java 버전: 5+ 문법만 사용 → Java 7 레거시에서도 그대로 컴파일된다
// 실행 진입점은 Item2Problem.java 다.

public class Item2Good {

    static final class NutritionFacts {
        private final int servingSize, servings, calories, fat, sodium;

        public static class Builder {
            private final int servingSize, servings;             // 필수 매개변수
            private int calories = 0, fat = 0, sodium = 0;       // 선택 매개변수 — 기본값으로 초기화

            public Builder(int servingSize, int servings) {
                this.servingSize = servingSize;
                this.servings = servings;
            }

            public Builder calories(int val) {
                calories = val;
                return this;
            }

            public Builder fat(int val) {
                fat = val;
                return this;
            }

            public Builder sodium(int val) {
                sodium = val;
                return this;
            }

            public NutritionFacts build() {
                return new NutritionFacts(this);
            }
        }

        private NutritionFacts(Builder b) {
            servingSize = b.servingSize;
            servings = b.servings;
            calories = b.calories;
            fat = b.fat;
            sodium = b.sodium;
            // 여러 필드에 걸친 불변식은 '복사를 마친 필드'로 검사한다 → 잘못된 객체는 세상에 나오지 못한다
            if (servingSize <= 0 || servings <= 0)
                throw new IllegalArgumentException("servingSize, servings 는 양수여야 함: " + servingSize + ", " + servings);
        }

        @Override
        public String toString() {
            return "칼로리 " + calories + "kcal, 지방 " + fat + "g, 나트륨 " + sodium + "mg";
        }
    }
}
