package programmers.lv1.list;

import java.util.Arrays;

// https://school.programmers.co.kr/learn/courses/30/lessons/42748
public class List1 {

    public static int[] solution(int[] array, int[][] commands) {

        int[] result = new int[commands.length];

        for (int a = 0; a < commands.length; a++) {

            int[] arr = new int[commands.length];

            int i = commands[a][0];
            int j = commands[a][1];
            int k = commands[a][2];

            arr = Arrays.copyOfRange(array, i - 1, j);

            Arrays.sort(arr);

            result[a] = arr[k - 1];
        }

        return result;

    }

    public static void main(String[] args) {

        int[] array = { 1, 5, 2, 6, 3, 7, 4 };
        int[][] commands = { { 2, 5, 3 }, { 4, 4, 1 }, { 1, 7, 3 } };

        System.out.println(Arrays.toString(solution(array, commands)));

    }

}
