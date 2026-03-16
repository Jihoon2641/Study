package programmers.lv1.sort;

// https://school.programmers.co.kr/learn/courses/30/lessons/12943
public class Sort3 {

    public static int solution(int num) {
        long n = num;
        int count = 0;

        while (n != 1) {
            if (count == 500) {
                return -1;
            }

            if (n % 2 == 0) {
                n /= 2;
            } else {
                n = n * 3 + 1;
            }

            count++;
        }

        return count;
    }

    public static void main(String[] args) {
        int num = 626331;

        System.out.println(solution(num));
    }

}
