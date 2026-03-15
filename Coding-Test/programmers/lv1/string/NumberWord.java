package programmers.lv1.string;

// https://school.programmers.co.kr/learn/courses/30/lessons/81301
public class NumberWord {

    public static int solution(String numbers) {
        String[] num = { "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine" };

        for (int i = 0; i < num.length; i++) {
            numbers = numbers.replaceAll(num[i], String.valueOf(i));
        }

        return Integer.parseInt(numbers);
    }

    public static void main(String[] args) {
        String numbers = "one4seveneight";

        System.out.println(solution(numbers));
    }

}
