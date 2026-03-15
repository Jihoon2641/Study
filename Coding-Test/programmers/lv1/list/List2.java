package programmers.lv1.list;

import java.util.Arrays;

// https://school.programmers.co.kr/learn/courses/30/lessons/12910
public class List2 {

    public static int[] solution(int[] arr, int divisor) {
        int[] result = Arrays.stream(arr).filter(x -> x % divisor == 0).sorted().toArray();

        return result.length == 0 ? new int[] { -1 } : result;
    }

    public static void main(String[] args) {
        int[] arr = { 5, 9, 7, 10 };
        int divisor = 5;

        System.out.println(Arrays.toString(solution(arr, divisor)));
    }

}
