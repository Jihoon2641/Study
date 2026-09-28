// ①·③ 공용 타입 — Item5Bad 와 Item5Good 이 함께 쓰는 사전(Lexicon)
// 대상 Java 버전: 5+ → Java 7 레거시에서도 그대로 컴파일된다
// 실행 진입점은 Item5Problem.java 다.

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Item5Common {

    interface Lexicon {
        boolean contains(String word);
    }

    static final class KoreanDictionary implements Lexicon {
        private final Set<String> words = new HashSet<String>(Arrays.asList("사과", "바나나"));

        public boolean contains(String word) {
            return words.contains(word);
        }
    }

    static final class EnglishDictionary implements Lexicon {
        private final Set<String> words = new HashSet<String>(Arrays.asList("apple", "banana"));

        public boolean contains(String word) {
            return words.contains(word);
        }
    }
}
