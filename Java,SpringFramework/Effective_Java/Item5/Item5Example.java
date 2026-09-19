// 대상 Java 버전: 7+ (Objects.requireNonNull). 람다를 쓰지 않아 Java 7 레거시에서 그대로 동작 → Legacy7 불필요.
// 실행: java Item5/Item5Example.java  (단일 파일 소스 실행은 Java 11+)

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public class Item5Example {

    interface Lexicon { boolean contains(String word); }

    static final class KoreanDictionary implements Lexicon {
        private final Set<String> words = new HashSet<String>(Arrays.asList("사과", "바나나"));
        public boolean contains(String word) { return words.contains(word); }
    }

    static final class EnglishDictionary implements Lexicon {
        private final Set<String> words = new HashSet<String>(Arrays.asList("apple", "banana"));
        public boolean contains(String word) { return words.contains(word); }
    }

    // ① 정적 유틸리티 클래스 — 사전을 클래스가 직접 정한다.
    //    다른 사전도 써야 해서 final 을 떼고 세터를 열었다(책이 "오류를 내기 쉽다"고 한 바로 그 형태).
    static final class BadSpellChecker {
        private static Lexicon dictionary = new KoreanDictionary();   // 모든 호출자가 공유하는 전역 상태
        private BadSpellChecker() { throw new AssertionError(); }
        static void setDictionary(Lexicon d) { dictionary = d; }
        static boolean isValid(String word) { return dictionary.contains(word); }
    }

    // ③ 의존 객체 주입 — 사전을 생성자로 받는다. 인스턴스마다 자기 사전을 가진다.
    static final class SpellChecker {
        private final Lexicon dictionary;
        SpellChecker(Lexicon dictionary) { this.dictionary = Objects.requireNonNull(dictionary); }
        boolean isValid(String word) { return dictionary.contains(word); }
    }

    // ② 무엇이 문제인가 — 두 요청 스레드가 사전 하나를 두고 겹친다
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== ① 정적 유틸리티 + 세터 ===");
        // 두 래치로 실행 순서를 고정했다. 확률에 기대지 않고 항상 같은 결과가 나온다.
        final CountDownLatch koreanSet = new CountDownLatch(1);
        final CountDownLatch englishSet = new CountDownLatch(1);

        Thread koreanRequest = new Thread(new Runnable() {
            public void run() {
                BadSpellChecker.setDictionary(new KoreanDictionary());   // 한국어 요청이 사전을 건다
                koreanSet.countDown();
                awaitQuietly(englishSet);                                 // 검사 직전에 다른 요청이 끼어든다
                System.out.println("한국어 요청: isValid(\"사과\")  = " + BadSpellChecker.isValid("사과"));
                // 출력: false  ← 자기가 건 사전이 아니라 영어 사전으로 검사됐다. 예외도 로그도 없다.
            }
        });
        Thread englishRequest = new Thread(new Runnable() {
            public void run() {
                awaitQuietly(koreanSet);
                BadSpellChecker.setDictionary(new EnglishDictionary());  // 영어 요청이 사전을 덮어쓴다
                englishSet.countDown();
            }
        });
        koreanRequest.start(); englishRequest.start();
        koreanRequest.join();  englishRequest.join();

        System.out.println();
        System.out.println("=== ③ 의존 객체 주입 ===");
        // 두 줄의 출력 순서는 실행마다 바뀔 수 있지만, 값은 항상 true 다.
        final CountDownLatch koreanReady = new CountDownLatch(1);
        final CountDownLatch englishReady = new CountDownLatch(1);

        Thread koreanRequest2 = new Thread(new Runnable() {
            public void run() {
                SpellChecker checker = new SpellChecker(new KoreanDictionary());   // 자기 사전을 들고 간다
                koreanReady.countDown();
                awaitQuietly(englishReady);                                        // 똑같이 끼어들게 해도
                System.out.println("한국어 요청: isValid(\"사과\")  = " + checker.isValid("사과"));
                // 출력: true  ← 다른 요청이 바꿀 수 있는 공유 상태가 없다
            }
        });
        Thread englishRequest2 = new Thread(new Runnable() {
            public void run() {
                awaitQuietly(koreanReady);
                SpellChecker checker = new SpellChecker(new EnglishDictionary());
                englishReady.countDown();
                System.out.println("영어 요청  : isValid(\"apple\") = " + checker.isValid("apple"));
                // 출력: true
            }
        });
        koreanRequest2.start(); englishRequest2.start();
        koreanRequest2.join();  englishRequest2.join();

        // new SpellChecker(null);
        // → NullPointerException: 잘못된 의존성은 생성 시점에 즉시 드러난다(아이템 49)
    }

    static void awaitQuietly(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
