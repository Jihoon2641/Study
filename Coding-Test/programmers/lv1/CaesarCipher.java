package programmers.lv1;

// https://school.programmers.co.kr/learn/courses/30/lessons/12926
public class CaesarCipher {

    public static String solution(String s, int n) {
        String answer = "";

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (c >= 'a' && c <= 'z') {
                c = (char) ((c - 'a' + n) % 26 + 'a');
            }
            if (c >= 'A' && c <= 'Z') {
                c = (char) ((c - 'A' + n) % 26 + 'A');
            }

            answer += c;
        }

        return answer;
    }

    public static void main(String[] args) {
        String s = "AB";
        int n = 1;

        System.out.println(solution(s, n));
    }

}
