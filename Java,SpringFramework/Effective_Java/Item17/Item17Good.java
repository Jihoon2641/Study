// ③ 개선된 코드 — 책의 Complex: 함수형 방식, 연산은 새 객체를 반환한다
// 대상 Java 버전: 7+ (Objects 는 Java 7+ — Java 7 레거시에서도 그대로 컴파일된다)
// 실행 진입점은 Item17Problem.java 다.

import java.util.Objects;

public class Item17Good {

    static final class Complex {
        public static final Complex ZERO = new Complex(0, 0);
        private final double re, im;

        private Complex(double re, double im) {
            this.re = re;
            this.im = im;
        }

        public static Complex valueOf(double re, double im) {
            return new Complex(re, im);
        }

        public Complex plus(Complex c) {
            return new Complex(re + c.re, im + c.im);   // this 는 그대로
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Complex)) return false;
            Complex c = (Complex) o;
            return Double.compare(c.re, re) == 0 && Double.compare(c.im, im) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(re, im);
        }

        @Override
        public String toString() {
            return "(" + re + " + " + im + "i)";
        }
    }
}
