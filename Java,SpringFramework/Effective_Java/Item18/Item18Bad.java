// ① 흔히 쓰는(나쁜) 코드 — HashSet 을 상속해 추가 횟수를 센다
// 대상 Java 버전: 7+ (Java 7 레거시에서도 그대로 컴파일된다)
// 실행 진입점은 Item18Problem.java 다.

import java.util.Collection;
import java.util.HashSet;

public class Item18Bad {

    static class InstrumentedHashSet<E> extends HashSet<E> {
        private int addCount = 0;

        @Override
        public boolean add(E e) {
            addCount++;
            return super.add(e);
        }

        @Override
        public boolean addAll(Collection<? extends E> c) {
            addCount += c.size();
            return super.addAll(c);   // 내부에서 this.add(e) 를 부른다 → 위의 add 가 또 센다
        }

        public int getAddCount() {
            return addCount;
        }
    }
}
