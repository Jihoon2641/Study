package programmers.lv1;

public class String6 {

    public static int solution(int n) {

        String num = String.valueOf(n);

        int result = 0;

        for (int i = 0; i < num.length(); i++) {
            result += num.charAt(i) - '0';
        }

        return result;
    }

    public static int solution2(int n) {
        return String.valueOf(n).chars().map(e -> e - '0').sum();
    }

    public static int solution3(int n) {
        int result = 0;

        while (n != 0) {
            result += n % 10;
            n /= 10;
        }

        return result;
    }

    public static void main(String[] args) {

        int n = 9842;

        System.out.println(solution3(n));
    }

}