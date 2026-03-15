package programmers.lv1.list;

import java.util.Arrays;

// https://school.programmers.co.kr/learn/courses/30/lessons/12932
public class List10 {

    public static int[] solution(long n) {
        int[] answer = new int[String.valueOf(n).length()];
        int i = 0;

        while (n != 0) {
            answer[i++] = (int) (n % 10);
            n /= 10;
        }

        return answer;
    }

    public static void main(String[] args) {
        long n = 12345;

        System.out.println(Arrays.toString(solution(n)));
    }

}
