package programmers.lv1.imple;

// https://school.programmers.co.kr/learn/courses/30/lessons/67256
public class Impl1 {

    public static String solution(int[] numbers, String hand) {
        String answer = "";

        int[][] pad = {
                { 1, 2, 3 },
                { 4, 5, 6 },
                { 7, 8, 9 },
                { 10, 0, 11 }
        };

        int[] left = { 3, 0 };
        int[] right = { 3, 2 };

        for (int num : numbers) {

            for (int i = 0; i < pad.length; i++) {
                for (int j = 0; j < pad[i].length; j++) {
                    if (pad[i][j] == num) {
                        if (num == 1 || num == 4 || num == 7) {
                            answer += "L";
                            left = new int[] { i, j };
                        } else if (num == 3 || num == 6 || num == 9) {
                            answer += "R";
                            right = new int[] { i, j };
                        } else {
                            int leftDist = Math.abs(left[0] - i) + Math.abs(left[1] - j);
                            int rightDist = Math.abs(right[0] - i) + Math.abs(right[1] - j);

                            if (leftDist < rightDist) {
                                answer += "L";
                                left = new int[] { i, j };
                            } else if (leftDist > rightDist) {
                                answer += "R";
                                right = new int[] { i, j };
                            } else {
                                if (hand.equals("left")) {
                                    answer += "L";
                                    left = new int[] { i, j };
                                } else {
                                    answer += "R";
                                    right = new int[] { i, j };
                                }
                            }
                        }
                    }
                }
            }

        }

        return answer;

    }

    public static String solution2(int[] numbers, String hand) {
        String answer = "";

        int left = 10;
        int right = 12;

        for (int num : numbers) {
            if (num == 1 || num == 4 || num == 7) {
                answer += "L";
                left = num;
            } else if (num == 3 || num == 6 || num == 9) {
                answer += "R";
                right = num;
            } else {
                if (num == 0) {
                    num = 11;
                }

                int leftDist = Math.abs(left - num) / 3 + Math.abs(left - num) % 3;
                int rightDist = Math.abs(right - num) / 3 + Math.abs(right - num) % 3;

                if (leftDist < rightDist) {
                    answer += "L";
                    left = num;
                } else if (leftDist > rightDist) {
                    answer += "R";
                    right = num;
                } else {
                    if (hand.equals("left")) {
                        answer += "L";
                        left = num;
                    } else {
                        answer += "R";
                        right = num;
                    }
                }
            }
        }

        return answer;
    }

    public static void main(String[] args) {
        int[] numbers = { 1, 3, 4, 5, 8, 2, 1, 4, 5, 9, 5 };
        String hand = "right";

        System.out.println(solution(numbers, hand));
    }

}
