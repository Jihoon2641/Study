package programmers.lv1.hash;

// https://school.programmers.co.kr/learn/courses/30/lessons/131705
public class Hash6 {

    public static int solution(int[] number) {
        int count = 0;

        for (int i = 0; i < number.length; i++) {
            for (int j = i + 1; j < number.length; j++) {
                for (int k = j + 1; k < number.length; k++) {
                    if (number[i] + number[j] + number[k] == 0) {
                        count++;
                    }
                }
            }
        }

        return count;
    }

    public static void main(String[] args) {
        int[] number = { -2, 3, 0, 2, -5 };

        System.out.println(solution(number));
    }

}
