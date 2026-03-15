package programmers.lv1.list;

import java.util.Arrays;

// https://school.programmers.co.kr/learn/courses/30/lessons/12982
public class List6 {

    public static int solution(int[] d, int budget) {
        Arrays.sort(d);

        int result = 0;

        for (int num : d) {
            if (budget - num >= 0) {
                budget -= num;
                result++;
            }
        }

        return result;
    }

    public static void main(String[] args) {
        int[] d = { 1, 3, 2, 5, 4 };
        int budget = 9;

        System.out.println(solution(d, budget));
    }

}
