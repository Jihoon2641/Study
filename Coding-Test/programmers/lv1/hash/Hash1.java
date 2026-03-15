package programmers.lv1.hash;

import java.util.HashMap;

// https://school.programmers.co.kr/learn/courses/30/lessons/42576
public class Hash1 {

    public static String solution(String[] participant, String[] completion) {
        HashMap<String, Integer> map = new HashMap<>();

        for (String p : participant) {
            map.put(p, map.getOrDefault(p, 0) + 1);
        }

        for (String c : completion) {
            map.put(c, map.get(c) - 1);
        }

        for (String p : map.keySet()) {
            if (map.get(p) > 0) {
                return p;
            }
        }

        return "";
    }

    public static void main(String[] args) {
        String[] participant = { "leo", "kiki", "eden", "leo" };
        String[] completion = { "eden", "kiki", "leo" };

        System.out.println(solution(participant, completion));
    }

}
