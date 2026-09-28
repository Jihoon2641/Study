// ① 흔히 쓰는(나쁜) 코드 — 절차적 방식: 연산이 자기 자신을 바꾼다
// 대상 Java 버전: 7+ (Objects 는 Java 7+ — Java 7 레거시에서도 그대로 컴파일된다)
// 실행 진입점은 Item17Problem.java 다.

import java.util.Objects;

public class Item17Bad {

    static final class MutableComplex {
        public static final MutableComplex ZERO = new MutableComplex(0, 0);  // final 은 '참조'만 고정
        private double re, im;

        MutableComplex(double re, double im) {
            this.re = re;
            this.im = im;
        }

        MutableComplex add(MutableComplex c) {   // 피연산자인 this 를 바꾸고 this 를 반환
            re += c.re;
            im += c.im;
            return this;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof MutableComplex)) return false;
            MutableComplex c = (MutableComplex) o;
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
