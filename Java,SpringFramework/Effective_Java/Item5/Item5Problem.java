// ② 무엇이 문제인가 — 두 요청 스레드가 사전 하나를 두고 겹친다. ① Item5Bad 와 ③ Item5Good 을 나란히 비교한다
// 대상 Java 버전: 7+ (람다 없이 익명 클래스 사용) → Java 7 레거시에서도 그대로 동작
// 실행 (Java 11~21): javac -d out/cli/Item5 Item5/*.java && java -cp out/cli/Item5 Item5Problem
// 실행 (Java 22+):   java Item5/Item5Problem.java   ← 같은 폴더의 다른 소스 파일을 자동으로 찾는다

import java.util.concurrent.CountDownLatch;

public class Item5Problem {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== ① 정적 유틸리티 + 세터 ===");
        // 두 래치로 실행 순서를 고정했다. 확률에 기대지 않고 항상 같은 결과가 나온다.
        final CountDownLatch koreanSet = new CountDownLatch(1);
        final CountDownLatch englishSet = new CountDownLatch(1);

        Thread koreanRequest = new Thread(new Runnable() {
            public void run() {
                Item5Bad.BadSpellChecker.setDictionary(new Item5Common.KoreanDictionary());   // 한국어 요청이 사전을 건다
                koreanSet.countDown();
                awaitQuietly(englishSet);                                                      // 검사 직전에 다른 요청이 끼어든다
                System.out.println("한국어 요청: isValid(\"사과\")  = " + Item5Bad.BadSpellChecker.isValid("사과"));
                // 출력: false  ← 자기가 건 사전이 아니라 영어 사전으로 검사됐다. 예외도 로그도 없다.
            }
        });
        Thread englishRequest = new Thread(new Runnable() {
            public void run() {
                awaitQuietly(koreanSet);
                Item5Bad.BadSpellChecker.setDictionary(new Item5Common.EnglishDictionary());  // 영어 요청이 사전을 덮어쓴다
                englishSet.countDown();
            }
        });
        koreanRequest.start();
        englishRequest.start();
        koreanRequest.join();
        englishRequest.join();

        System.out.println();
        System.out.println("=== ③ 의존 객체 주입 ===");
        // 두 줄의 출력 순서는 실행마다 바뀔 수 있지만, 값은 항상 true 다.
        final CountDownLatch koreanReady = new CountDownLatch(1);
        final CountDownLatch englishReady = new CountDownLatch(1);

        Thread koreanRequest2 = new Thread(new Runnable() {
            public void run() {
                Item5Good.SpellChecker checker = new Item5Good.SpellChecker(new Item5Common.KoreanDictionary());   // 자기 사전을 들고 간다
                koreanReady.countDown();
                awaitQuietly(englishReady);                                                                     // 똑같이 끼어들게 해도
                System.out.println("한국어 요청: isValid(\"사과\")  = " + checker.isValid("사과"));
                // 출력: true  ← 다른 요청이 바꿀 수 있는 공유 상태가 없다
            }
        });
        Thread englishRequest2 = new Thread(new Runnable() {
            public void run() {
                awaitQuietly(koreanReady);
                Item5Good.SpellChecker checker = new Item5Good.SpellChecker(new Item5Common.EnglishDictionary());
                englishReady.countDown();
                System.out.println("영어 요청  : isValid(\"apple\") = " + checker.isValid("apple"));
                // 출력: true
            }
        });
        koreanRequest2.start();
        englishRequest2.start();
        koreanRequest2.join();
        englishRequest2.join();

        // new Item5Good.SpellChecker(null);
        // → NullPointerException: 잘못된 의존성은 생성 시점에 즉시 드러난다(아이템 49)
    }

    static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
