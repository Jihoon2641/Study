// ③ 개선된 코드 — 상속 대신 컴포지션 + 전달
// 대상 Java 버전: 7+ (Java 7 레거시에서도 그대로 컴파일된다)
// 실행 진입점은 Item18Problem.java 다.

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

public class Item18Good {

    // 재사용 가능한 전달 클래스
    static class ForwardingSet<E> implements Set<E> {
        private final Set<E> s;   // 컴포지션: 기존 Set 을 필드로 가진다

        ForwardingSet(Set<E> s) {
            this.s = s;
        }

        public void clear() {
            s.clear();
        }

        public boolean contains(Object o) {
            return s.contains(o);
        }

        public boolean isEmpty() {
            return s.isEmpty();
        }

        public int size() {
            return s.size();
        }

        public Iterator<E> iterator() {
            return s.iterator();
        }

        public boolean add(E e) {
            return s.add(e);
        }

        public boolean remove(Object o) {
            return s.remove(o);
        }

        public boolean containsAll(Collection<?> c) {
            return s.containsAll(c);
        }

        public boolean addAll(Collection<? extends E> c) {
            return s.addAll(c);   // 수신 객체가 this 가 아니라 s
        }

        public boolean removeAll(Collection<?> c) {
            return s.removeAll(c);
        }

        public boolean retainAll(Collection<?> c) {
            return s.retainAll(c);
        }

        public Object[] toArray() {
            return s.toArray();
        }

        public <T> T[] toArray(T[] a) {
            return s.toArray(a);
        }

        @Override
        public boolean equals(Object o) {
            return s.equals(o);
        }

        @Override
        public int hashCode() {
            return s.hashCode();
        }

        @Override
        public String toString() {
            return s.toString();
        }
    }

    // 래퍼 클래스 — 관심 있는 메서드만 재정의한다
    static class InstrumentedSet<E> extends ForwardingSet<E> {
        private int addCount = 0;

        InstrumentedSet(Set<E> s) {
            super(s);
        }

        @Override
        public boolean add(E e) {
            addCount++;
            return super.add(e);
        }

        @Override
        public boolean addAll(Collection<? extends E> c) {
            addCount += c.size();
            return super.addAll(c);   // HashSet 은 이 래퍼를 모른다 → 역호출 없음
        }

        public int getAddCount() {
            return addCount;
        }
    }
}
