package programmers.lv1.list;

import java.util.Arrays;

// https://school.programmers.co.kr/learn/courses/30/lessons/12954
public class List9 {

    public static long[] solution(int x, int n) {
        long[] answer = new long[n];

        for (int i = 0; i < n; i++) {
            answer[i] = (long) x * (i + 1);
        }

        return answer;
    }

    public static void main(String[] args) {
        int x = 2, n = 5;

        System.out.println(Arrays.toString(solution(x, n)));
    }

}
