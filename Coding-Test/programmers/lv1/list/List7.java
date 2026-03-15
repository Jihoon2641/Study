package programmers.lv1.list;

// https://school.programmers.co.kr/learn/courses/30/lessons/12944
public class List7 {

    public static double solution(int[] arr) {
        double answer = 0;

        for (int num : arr) {
            answer += num;
        }

        return answer / arr.length;
    }

    public static void main(String[] args) {
        int[] arr = { 1, 2, 3, 4 };

        System.out.println(solution(arr));
    }

}
