package programmers.lv1.imple;

// https://school.programmers.co.kr/learn/courses/30/lessons/17681
public class Impl4 {

    public static String[] solution(int n, int[] arr1, int[] arr2) {
        String[] answer = new String[n];

        for (int i = 0; i < n; i++) {
            String s1 = Integer.toBinaryString(arr1[i]);
            String s2 = Integer.toBinaryString(arr2[i]);

            while (s1.length() < n) {
                s1 = "0" + s1;
            }
            while (s2.length() < n) {
                s2 = "0" + s2;
            }

            String result = "";
            for (int j = 0; j < n; j++) {
                if (s1.charAt(j) == '1' || s2.charAt(j) == '1') {
                    result += "#";
                } else {
                    result += " ";
                }
            }
            answer[i] = result;
        }

        return answer;
    }

    public static void main(String[] args) {
        int n = 5;
        int[] arr1 = { 9, 20, 28, 18, 11 };
        int[] arr2 = { 30, 1, 21, 17, 28 };

        String[] result = solution(n, arr1, arr2);

        for (String s : result) {
            System.out.println(s);
        }
    }

}
