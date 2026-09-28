// ② 무엇이 문제인가 — ① Item2Bad 를 실제로 깨뜨리고, ③ Item2Good 과 나란히 비교한다
// 대상 Java 버전: 5+ 문법만 사용 → Java 7 레거시에서도 그대로 컴파일된다
// 실행 (Java 11~21): javac -d out/cli/Item2 Item2/*.java && java -cp out/cli/Item2 Item2Problem
// 실행 (Java 22+):   java Item2/Item2Problem.java   ← 같은 폴더의 다른 소스 파일을 자동으로 찾는다

public class Item2Problem {

    public static void main(String[] args) {
        System.out.println("=== ①-1 점층적 생성자: 같은 int 두 개의 순서를 바꿔 씀 ===");
        // 의도: 칼로리 100, 지방 0g, 나트륨 35mg. (…, fat, sodium) 순서를 착각해 35와 0을 뒤집었다.
        Item2Bad.TelescopingNutritionFacts cola = new Item2Bad.TelescopingNutritionFacts(240, 8, 100, 35, 0);
        System.out.println("라벨 = " + cola);
        // 출력: 칼로리 100kcal, 지방 35g, 나트륨 0mg  ← 컴파일 OK, 예외 없음. 잘못된 라벨이 그대로 인쇄된다

        System.out.println();
        System.out.println("=== ①-2 자바빈즈: 세터 하나 빠뜨림 ===");
        Item2Bad.JavaBeansNutritionFacts beans = new Item2Bad.JavaBeansNutritionFacts();
        beans.setServingSize(240);
        beans.setCalories(100);
        // beans.setServings(8);   ← 필드가 추가된 뒤 이 호출 한 줄이 누락됐다. 컴파일러는 모른다.
        System.out.println("총 용량 = " + beans.totalMl() + "ml");
        // 출력: 총 용량 = -240ml  ← 생성은 '성공'했고, 한참 뒤 계산 결과에서야 드러난다

        System.out.println();
        System.out.println("=== ③ 빌더 ===");
        Item2Good.NutritionFacts good = new Item2Good.NutritionFacts.Builder(240, 8).calories(100).sodium(35).build();
        System.out.println("라벨 = " + good);
        // 출력: 칼로리 100kcal, 지방 0g, 나트륨 35mg  ← 이름으로 넣으니 순서를 뒤집을 수가 없다

        // Item2Good.NutritionFacts noServings = new Item2Good.NutritionFacts.Builder(240).calories(100).build();
        // 컴파일 에러: constructor Builder in class Builder cannot be applied to given types
        //            (필수 값 누락이 실행 전에 막힌다)

        try {
            new Item2Good.NutritionFacts.Builder(240, 0).calories(100).build();
        } catch (IllegalArgumentException e) {
            System.out.println("build() 시점에 차단: " + e.getMessage());
            // 출력: build() 시점에 차단: servingSize, servings 는 양수여야 함: 240, 0
        }
    }
}
