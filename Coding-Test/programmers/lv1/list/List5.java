package programmers.lv1.list;

import java.util.*;

// https://school.programmers.co.kr/learn/courses/30/lessons/68644
public class List5 {

    // TreeSet - 자동 정렬, 중복 제거
    public static TreeSet<Integer> solution(int[] answers) {
        TreeSet<Integer> set = new TreeSet<>();

        for (int i = 0; i < answers.length - 1; i++) {
            for (int j = i + 1; j < answers.length; j++) {
                set.add(answers[i] + answers[j]);
            }
        }

        return set;
    }

    public static ArrayList<Integer> solution2(int[] answers) {
        ArrayList<Integer> list = new ArrayList<>();

        for (int i = 0; i < answers.length - 1; i++) {

            for (int j = i + 1; j < answers.length; j++) {
                int sum = answers[i] + answers[j];

                if (!list.contains(sum)) {
                    list.add(sum);
                }

            }
        }

        Collections.sort(list);
        return list;
    }

    public static void main(String[] args) {
        int[] answers = { 2, 1, 3, 4, 1 };

        System.out.println(solution(answers));
    }

}
