package programmers.lv1.imple;

import java.util.Stack;

// https://school.programmers.co.kr/learn/courses/30/lessons/64061
public class Impl2 {

    public static int solution(int[][] board, int[] moves) {
        int answer = 0;

        Stack<Integer> result = new Stack<>();

        for (int move : moves) {
            for (int i = 0; i < board.length; i++) {
                if (board[i][move - 1] != 0) {
                    if (!result.isEmpty() && result.peek() == board[i][move - 1]) {
                        result.pop();
                        answer += 2;
                    } else {
                        result.push(board[i][move - 1]);
                    }
                    board[i][move - 1] = 0;
                    break;
                }
            }
        }

        return answer;
    }

    public static void main(String[] args) {
        int[][] board = {
                { 0, 0, 0, 0, 0 },
                { 0, 0, 1, 0, 3 },
                { 0, 2, 5, 0, 1 },
                { 4, 2, 4, 4, 2 },
                { 3, 5, 1, 3, 1 }
        };
        int[] moves = { 1, 5, 3, 5, 1, 2, 1, 4 };

        System.out.println(solution(board, moves));
    }
}
