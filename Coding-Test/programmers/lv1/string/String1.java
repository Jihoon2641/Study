package programmers.lv1.string;

// https://school.programmers.co.kr/learn/courses/30/lessons/12918
public class String1 {

    public static boolean solution(String s) {
        // 길이가 4 또는 6이 아니면 false
        if (s.length() != 4 && s.length() != 6) {
            return false;
        }

        // 숫자로만 구성되어 있는지 확인
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }

        return true;
    }

    public static void main(String[] args) {
        System.out.println(solution("a234")); // false (문자 포함)
        System.out.println(solution("1234")); // true
        System.out.println(solution("12345")); // false (길이 5)
        System.out.println(solution("123456")); // true
    }

}
