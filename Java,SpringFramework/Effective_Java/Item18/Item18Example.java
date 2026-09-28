// 대상 Java 버전: 7+ (책의 List.of 대신 Arrays.asList 를 써서 Java 7 문법만 사용 — Legacy7 클래스 불필요)
// 실행: java Item18/Item18Example.java  (단일 파일 소스 실행은 Java 11+)

import java.util.*;

public class Item18Example {

    // ① 흔히 쓰는(나쁜) 코드 — HashSet 을 상속해 추가 횟수를 센다
    static class InstrumentedHashSet<E> extends HashSet<E> {
        private int addCount = 0;

        @Override public boolean add(E e) {
            addCount++;
            return super.add(e);
        }
        @Override public boolean addAll(Collection<? extends E> c) {
            addCount += c.size();
            return super.addAll(c);   // 내부에서 this.add(e) 를 부른다 → 위의 add 가 또 센다
        }
        public int getAddCount() { return addCount; }
    }

    // ③ 개선된 코드 — 재사용 가능한 전달 클래스
    static class ForwardingSet<E> implements Set<E> {
        private final Set<E> s;   // 컴포지션: 기존 Set 을 필드로 가진다
        ForwardingSet(Set<E> s) { this.s = s; }

        public void clear()                              { s.clear(); }
        public boolean contains(Object o)                { return s.contains(o); }
        public boolean isEmpty()                         { return s.isEmpty(); }
        public int size()                                { return s.size(); }
        public Iterator<E> iterator()                    { return s.iterator(); }
        public boolean add(E e)                          { return s.add(e); }
        public boolean remove(Object o)                  { return s.remove(o); }
        public boolean containsAll(Collection<?> c)      { return s.containsAll(c); }
        public boolean addAll(Collection<? extends E> c) { return s.addAll(c); }  // 수신 객체가 this 가 아니라 s
        public boolean removeAll(Collection<?> c)        { return s.removeAll(c); }
        public boolean retainAll(Collection<?> c)        { return s.retainAll(c); }
        public Object[] toArray()                        { return s.toArray(); }
        public <T> T[] toArray(T[] a)                    { return s.toArray(a); }
        @Override public boolean equals(Object o)        { return s.equals(o); }
        @Override public int hashCode()                  { return s.hashCode(); }
        @Override public String toString()               { return s.toString(); }
    }

    // ③ 개선된 코드 — 래퍼 클래스. 관심 있는 메서드만 재정의한다
    static class InstrumentedSet<E> extends ForwardingSet<E> {
        private int addCount = 0;
        InstrumentedSet(Set<E> s) { super(s); }

        @Override public boolean add(E e) {
            addCount++;
            return super.add(e);
        }
        @Override public boolean addAll(Collection<? extends E> c) {
            addCount += c.size();
            return super.addAll(c);   // HashSet 은 이 래퍼를 모른다 → 역호출 없음
        }
        public int getAddCount() { return addCount; }
    }

    // ② 무엇이 문제인가 — 말로 때우지 말고 여기서 실제로 깨뜨린다
    public static void main(String[] args) {
        List<String> words = Arrays.asList("틱", "탁탁", "펑");   // 책: List.of (Java 9+)

        System.out.println("=== ① 상속: InstrumentedHashSet ===");
        InstrumentedHashSet<String> bad = new InstrumentedHashSet<>();
        bad.add("하나");
        System.out.println("add 1번 → addCount = " + bad.getAddCount());
        // 출력: add 1번 → addCount = 1  ← add 만 쓰는 테스트는 통과한다

        bad = new InstrumentedHashSet<>();
        bad.addAll(words);
        System.out.println("addAll(3개) → addCount = " + bad.getAddCount() + ", 실제 원소 수 = " + bad.size());
        // 출력: addAll(3개) → addCount = 6, 실제 원소 수 = 3
        //       ← 컴파일 에러도, 예외도, 로그도 없다. 숫자만 조용히 두 배가 된다.
        //         원인은 HashSet(정확히는 AbstractCollection)의 문서화되지 않은 구현 세부다.

        System.out.println();
        System.out.println("=== ③ 컴포지션: InstrumentedSet ===");
        InstrumentedSet<String> good = new InstrumentedSet<>(new HashSet<String>());
        good.addAll(words);
        System.out.println("addAll(3개) → addCount = " + good.getAddCount() + ", 실제 원소 수 = " + good.size());
        // 출력: addAll(3개) → addCount = 3, 실제 원소 수 = 3

        // 덤: 어떤 Set 구현이든 감쌀 수 있다. InstrumentedHashSet 은 HashSet 에만 붙는다.
        InstrumentedSet<String> sorted = new InstrumentedSet<>(new TreeSet<String>());
        sorted.addAll(words);
        sorted.add("틱");   // 중복이라 실제로는 추가되지 않지만 "시도 횟수"는 센다
        System.out.println("TreeSet 감싸기 → " + sorted + ", addCount = " + sorted.getAddCount());
        // 출력: TreeSet 감싸기 → [탁탁, 틱, 펑], addCount = 4
    }
}
