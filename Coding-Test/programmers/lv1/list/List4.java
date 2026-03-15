package programmers.lv1.list;

import java.util.ArrayList;
import java.util.Stack;

// https://school.programmers.co.kr/learn/courses/30/lessons/12906
public class List4 {

    public static int[] solution(int[] arr) {
        ArrayList<Integer> list = new ArrayList<>();

        for (int i = 0; i < arr.length; i++) {
            list.add(arr[i]);

            int last = list.size() - 1;
            if (list.size() > 1 && list.get(last).equals(list.get(last - 1))) {
                list.remove(last);
            }
        }

        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    public static Stack<Integer> solution2(int[] arr) {
        Stack<Integer> stack = new Stack<>();

        for (int num : arr) {
            if (stack.size() == 0 || stack.peek() != num) {
                stack.push(num);
            }
        }

        return stack;
    }

    public static void main(String[] args) {
        int[] arr = { 1, 1, 3, 3, 0, 1, 1 };

        System.out.println(solution2(arr));
    }

}
