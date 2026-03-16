package programmers.lv1.sort;

import java.util.ArrayList;
import java.util.Collections;

public class Sort1 {

    public static long solution(long n) {
        long answer = 0;

        ArrayList<Long> arr = new ArrayList<>();

        while (n != 0) {
            arr.add(n % 10);
            n /= 10;
        }

        Collections.sort(arr, Collections.reverseOrder());

        for (long num : arr) {
            answer = answer * 10 + num;
        }

        return answer;
    }

    public static void main(String[] args) {
        long n = 118372;

        System.out.println(solution(n));
    }

}
