package programmers.lv1;

// https://school.programmers.co.kr/learn/courses/30/lessons/12916
public class String5 {

    public static boolean solution(String s) {
        s = s.toLowerCase();

        s = s.replaceAll("[^py]", "");

        int pcnt = 0;
        int ycnt = 0;

        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == 'p') {
                pcnt++;
            } else {
                ycnt++;
            }
        }

        return pcnt == ycnt;
    }

    public static boolean solution2(String s) {
        s = s.toLowerCase();

        return s.chars().filter(e -> 'p' == e).count() == s.chars().filter(e -> 'y' == e).count();
    }

    public static void main(String[] args) {

        String s = "pPoooyYp";

        System.out.println(solution2(s));

    }
}
