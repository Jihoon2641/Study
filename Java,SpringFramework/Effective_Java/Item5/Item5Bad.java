// ① 흔히 쓰는(나쁜) 코드 — 정적 유틸리티 클래스가 사전을 직접 정한다
// 대상 Java 버전: 5+ → Java 7 레거시에서도 그대로 컴파일된다
// 실행 진입점은 Item5Problem.java 다.

public class Item5Bad {

    // 다른 사전도 써야 해서 final 을 떼고 세터를 열었다(책이 "오류를 내기 쉽다"고 한 바로 그 형태).
    static final class BadSpellChecker {
        private static Item5Common.Lexicon dictionary = new Item5Common.KoreanDictionary();   // 모든 호출자가 공유하는 전역 상태

        private BadSpellChecker() {
            throw new AssertionError();
        }

        static void setDictionary(Item5Common.Lexicon d) {
            dictionary = d;
        }

        static boolean isValid(String word) {
            return dictionary.contains(word);
        }
    }
}
