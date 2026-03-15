package programmers.lv1.string;

import java.util.Arrays;

// https://school.programmers.co.kr/learn/courses/30/lessons/12915
public class String3 {

    public static String[] solution(String[] strings, int n) {
        String[] arr = new String[strings.length];

        int cnt = 0;

        Arrays.sort(strings);

        for (int i = 97; i < 123; i++) {
            for (int j = 0; j < strings.length; j++) {
                if (strings[j].charAt(n) == (char) i) {
                    arr[cnt++] = strings[j];
                }
            }
        }

        return arr;
    }

    public static String[] solution2(String[] strings, int n) {

        Arrays.sort(strings, (s1, s2) -> {
            int result = s1.charAt(n) - s2.charAt(n);
            if (result == 0) {
                return s1.compareTo(s2);
            }
            return result;
        });

        return strings;
    }

    public static void main(String[] args) {
        String[] strings = { "abce", "abcd", "cdx" };
        int n = 2;

        System.out.println(Arrays.toString(solution(strings, n)));
    }

}
