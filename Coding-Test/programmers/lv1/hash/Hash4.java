package programmers.lv1.hash;

import java.util.Arrays;
import java.util.HashMap;

// https://school.programmers.co.kr/learn/courses/30/lessons/77484
public class Hash4 {

    public static int[] solution(int[] lottos, int[] win_nums) {
        HashMap<Integer, Integer> map = new HashMap<>();

        for (int num : lottos) {
            map.put(num, map.getOrDefault(num, 0) + 1);
        }

        for (int num : win_nums) {
            map.put(num, map.getOrDefault(num, 0) + 1);
        }

        int min = 0;

        for (Integer k : map.keySet()) {
            if (k != 0 && map.get(k) == 2) {
                min++;
            }
        }

        int max = min + map.getOrDefault(0, 0);

        int[] rank = { 6, 6, 5, 4, 3, 2, 1 };

        return new int[] { rank[max], rank[min] };

    }

    public static void main(String[] args) {
        int[] lottos = { 44, 1, 0, 0, 31, 25 };
        int[] win_nums = { 31, 10, 45, 1, 6, 19 };

        System.out.println(Arrays.toString(solution(lottos, win_nums)));
    }

}
