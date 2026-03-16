package programmers.lv1.imple;

import java.util.Stack;

// https://school.programmers.co.kr/learn/courses/30/lessons/17682
public class Impl3 {

    public static int solution(String dartResult) {
        Stack<Integer> stack = new Stack<>();
        int num = 0;

        for (char c : dartResult.toCharArray()) {
            if (Character.isDigit(c)) {
                num = num * 10 + (c - '0');
            } else if (c == 'S') {
                stack.push(num);
                num = 0;
            } else if (c == 'D') {
                stack.push((int) Math.pow(num, 2));
                num = 0;
            } else if (c == 'T') {
                stack.push((int) Math.pow(num, 3));
                num = 0;
            } else if (c == '#') {
                stack.push(-stack.pop());
            } else if (c == '*') {
                int top = stack.pop();
                if (!stack.isEmpty())
                    stack.push(stack.pop() * 2);
                stack.push(top * 2);
            }
        }

        return stack.stream().mapToInt(Integer::intValue).sum();
    }

    public static void main(String[] args) {
        String dartResult = "1S2D*3T";

        System.out.println(solution(dartResult));
    }

}
