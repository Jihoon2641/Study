// 대상 Java 버전: 7+ (Objects 는 Java 7+ — Java 7 레거시에서도 그대로 컴파일된다)
// 실행: java Item17/Item17Example.java  (단일 파일 소스 실행은 Java 11+)

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class Item17Example {

    // ① 흔히 쓰는(나쁜) 코드 — 절차적 방식: 연산이 자기 자신을 바꾼다
    static final class MutableComplex {
        public static final MutableComplex ZERO = new MutableComplex(0, 0);  // final 은 '참조'만 고정
        private double re, im;

        MutableComplex(double re, double im) { this.re = re; this.im = im; }

        MutableComplex add(MutableComplex c) {   // 피연산자인 this 를 바꾸고 this 를 반환
            re += c.re;
            im += c.im;
            return this;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof MutableComplex)) return false;
            MutableComplex c = (MutableComplex) o;
            return Double.compare(c.re, re) == 0 && Double.compare(c.im, im) == 0;
        }
        @Override public int hashCode() { return Objects.hash(re, im); }
        @Override public String toString() { return "(" + re + " + " + im + "i)"; }
    }

    // ③ 개선된 코드 — 책의 Complex: 함수형 방식, 연산은 새 객체를 반환한다
    static final class Complex {
        public static final Complex ZERO = new Complex(0, 0);
        private final double re, im;

        private Complex(double re, double im) { this.re = re; this.im = im; }
        public static Complex valueOf(double re, double im) { return new Complex(re, im); }

        public Complex plus(Complex c) { return new Complex(re + c.re, im + c.im); }  // this 는 그대로

        @Override public boolean equals(Object o) {
            if (!(o instanceof Complex)) return false;
            Complex c = (Complex) o;
            return Double.compare(c.re, re) == 0 && Double.compare(c.im, im) == 0;
        }
        @Override public int hashCode() { return Objects.hash(re, im); }
        @Override public String toString() { return "(" + re + " + " + im + "i)"; }
    }

    // 다른 모듈의 합계 유틸 — "0부터 더한다"는 자연스러운 코드
    static MutableComplex sumBad(List<MutableComplex> xs) {
        MutableComplex sum = MutableComplex.ZERO;   // 상수를 누산기 시작값으로 썼다
        for (MutableComplex x : xs) sum.add(x);
        return sum;
    }

    static Complex sumGood(List<Complex> xs) {
        Complex sum = Complex.ZERO;
        for (Complex x : xs) sum = sum.plus(x);     // 결과를 다시 대입해야 한다
        return sum;
    }

    // ② 무엇이 문제인가 — 말로 때우지 말고 여기서 실제로 깨뜨린다
    public static void main(String[] args) {
        System.out.println("=== ① MutableComplex: 공유한 상수가 오염된다 ===");
        System.out.println("1차 합계 = " + sumBad(Arrays.asList(new MutableComplex(1, 1), new MutableComplex(2, 2))));
        // 출력: 1차 합계 = (3.0 + 3.0i)  ← 첫 호출은 맞다. 단위 테스트도 한 번만 호출해서 통과한다
        System.out.println("2차 합계 = " + sumBad(Arrays.asList(new MutableComplex(1, 0))));
        // 출력: 2차 합계 = (4.0 + 3.0i)  ← 기대값 (1.0 + 0.0i). 이전 호출의 결과가 섞였다
        System.out.println("ZERO     = " + MutableComplex.ZERO);
        // 출력: ZERO     = (4.0 + 3.0i)  ← 이제 ZERO 를 쓰는 모든 코드가 틀린다. 예외는 없다

        MutableComplex key = new MutableComplex(1, 1);
        Set<MutableComplex> seen = new HashSet<>();
        seen.add(key);
        key.add(new MutableComplex(1, 0));          // 집합에 넣은 뒤 원소를 바꿨다
        System.out.println("seen.contains(key) = " + seen.contains(key) + ", size = " + seen.size());
        // 출력: seen.contains(key) = false, size = 1  ← 원소는 들어 있는데 찾을 수 없다
        //        (옛 해시 버킷에 남아 있어서 새 hashCode 로는 도달하지 못한다)

        System.out.println();
        System.out.println("=== ③ Complex: 공유해도 안전하다 ===");
        System.out.println("1차 합계 = " + sumGood(Arrays.asList(Complex.valueOf(1, 1), Complex.valueOf(2, 2))));
        // 출력: 1차 합계 = (3.0 + 3.0i)
        System.out.println("2차 합계 = " + sumGood(Arrays.asList(Complex.valueOf(1, 0))));
        // 출력: 2차 합계 = (1.0 + 0.0i)  ← 호출 순서와 무관하게 같은 답
        System.out.println("ZERO     = " + Complex.ZERO);
        // 출력: ZERO     = (0.0 + 0.0i)

        Complex k = Complex.valueOf(1, 1);
        Set<Complex> seen2 = new HashSet<>();
        seen2.add(k);
        k.plus(Complex.valueOf(1, 0));              // 반환값을 버렸다 — k 는 그대로다
        System.out.println("seen2.contains(k) = " + seen2.contains(k));
        // 출력: seen2.contains(k) = true
    }
}
