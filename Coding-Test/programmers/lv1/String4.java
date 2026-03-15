package programmers.lv1;

import java.util.Arrays;

// https://school.programmers.co.kr/learn/courses/30/lessons/12917
public class String4 {

    public static String solution(String s) {
        Character[] chars = new Character[s.length()];
        for (int i = 0; i < s.length(); i++) {
            chars[i] = s.charAt(i);
        }

        Arrays.sort(chars, (c1, c2) -> c2 - c1);

        StringBuilder sb = new StringBuilder();
        for (char c : chars)
            sb.append(c);

        return sb.toString();
    }

    public static void main(String[] args) {
        String s = "Zbcdefg";

        System.out.println(solution(s));
    }

}
