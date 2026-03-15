package programmers.lv1.list;

import java.util.Arrays;

// https://school.programmers.co.kr/learn/courses/30/lessons/12935
public class List3 {

    public static int[] solution(int[] arr) {
        int min = Arrays.stream(arr).min().getAsInt();

        int[] result = Arrays.stream(arr).filter(x -> x != min).toArray();

        return result.length == 0 || result[0] == 10 ? new int[] { -1 } : result;
    }

    public static int[] solution2(int[] arr) {
        if (arr.length == 1) {
            return new int[] { -1 };
        }

        int[] new_arr = new int[arr.length - 1];

        int min = arr[0];

        for (int i = 1; i < arr.length; i++) {
            if (arr[i] < min) {
                min = arr[i];
            }
        }

        boolean flag = true;

        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == min) {
                flag = false;
                continue;
            }

            if (flag) {
                new_arr[i] = arr[i];
            } else {
                new_arr[i - 1] = arr[i];
            }
        }

        return new_arr;
    }

    public static void main(String[] args) {
        int[] arr = { 4, 3, 2, 1 };

        System.out.println(Arrays.toString(solution(arr)));
    }

}
