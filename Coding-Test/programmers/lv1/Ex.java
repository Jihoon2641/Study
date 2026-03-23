package programmers.lv1;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Stack;

public class Ex {

    public static boolean solution(String s) {
        s = s.toLowerCase();

        return s.chars().filter(c -> c == 'p').count() == s.chars().filter(c -> c == 'y').count();
    }

    public static void main(String[] args) {
        String s = "pPoooyY";
        System.out.println(solution(s));
    }

}
