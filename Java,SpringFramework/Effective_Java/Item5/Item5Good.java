// ③ 개선된 코드 — 의존 객체 주입: 사전을 생성자로 받는다. 인스턴스마다 자기 사전을 가진다.
// 대상 Java 버전: 7+ (Objects.requireNonNull) → Java 7 레거시에서도 그대로 컴파일된다
// 실행 진입점은 Item5Problem.java 다.

import java.util.Objects;

public class Item5Good {

    static final class SpellChecker {
        private final Item5Common.Lexicon dictionary;

        SpellChecker(Item5Common.Lexicon dictionary) {
            this.dictionary = Objects.requireNonNull(dictionary);
        }

        boolean isValid(String word) {
            return dictionary.contains(word);
        }
    }
}
