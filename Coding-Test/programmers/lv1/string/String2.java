package programmers.lv1.string;

// https://school.programmers.co.kr/learn/courses/30/lessons/12903
public class String2 {

    public static String solution(String s) {
        return s.length() % 2 == 0 ? s.substring(s.length() / 2 - 1, s.length() / 2 + 1)
                : s.substring(s.length() / 2, s.length() / 2 + 1);
    }

    public static void main(String[] args) {
        System.out.println(solution("abcde"));
        System.out.println(solution("qwer"));
    }
}
