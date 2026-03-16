package programmers.lv1.sort;

// https://school.programmers.co.kr/learn/courses/30/lessons/12947
public class Sort2 {

    public static boolean solution(int x) {
        int sum = 0;
        int num = x;
        while (x != 0) {
            sum += x % 10;
            x /= 10;
        }

        return num % sum == 0;
    }

    public static void main(String[] args) {
        int x = 18;

        System.out.println(solution(x));
    }
}
