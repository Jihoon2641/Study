package programmers.lv1.hash;

import java.util.HashMap;

// https://school.programmers.co.kr/learn/courses/30/lessons/86051
public class Hash3 {

    public static int solution(int[] numbers) {
        HashMap<Integer, Integer> map = new HashMap<>();

        for (int i = 0; i < 10; i++) {
            map.put(i, 1);
        }

        for (int num : numbers) {
            map.put(num, map.get(num) - 1);
        }

        int sum = 0;

        for (Integer k : map.keySet()) {
            if (map.get(k) > 0) {
                sum += map.get(k);
            }
        }

        return sum;
    }

    public static void main(String[] args) {
        int[] numbers = { 1, 2, 3, 4, 6, 7, 8, 0 };

        System.out.println(solution(numbers));
    }
}
