package programmers.lv1;

// https://school.programmers.co.kr/learn/courses/30/lessons/12948
public class String7 {

    public static String solution(String s) {
        String s2 = s.substring(s.length() - 4, s.length());

        String answer = "";

        for (int i = 0; i < s.length() - 4; i++) {
            answer += "*";
        }

        return answer + s2;
    }

    public static String solution2(String s) {
        String s2 = s.substring(s.length() - 4, s.length());

        return "*".repeat(s.length() - 4) + s2;
    }

    public static String solution3(String s) {
        return s.replaceAll(".(?=.{4})", "*");
    }

    public static void main(String[] args) {
        String s = "01033221234";

        System.out.println(solution3(s));
    }

}