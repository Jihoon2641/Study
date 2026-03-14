package programmers.lv1;

// https://school.programmers.co.kr/learn/courses/30/lessons/12930
public class StrangeString {

    public static String solution(String s) {
        StringBuilder answer = new StringBuilder();

        int index = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (c == ' ') {
                index = 0;
                answer.append(c);
            } else {
                if (index % 2 == 0) {
                    answer.append(Character.toUpperCase(c));
                } else {
                    answer.append(Character.toLowerCase(c));
                }
                index++;
            }
        }

        return answer.toString();
    }

    public static void main(String[] args) {
        String s = "try hello world";

        System.out.println(solution(s));
    }

}
