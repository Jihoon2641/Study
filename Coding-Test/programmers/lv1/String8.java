package programmers.lv1;

public class String8 {

    public static String solution(String[] s) {

        for (int i = 0; i < s.length; i++) {
            if (s[i].equals("Kim")) {
                return "김서방은 " + i + "번째에 있습니다";
            }
        }

        return "";
    }

    public static void main(String[] args) {
        String[] s = { "Kim", "seoul" };

        System.out.println(solution(s));
    }

}
