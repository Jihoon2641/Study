package programmers.lv1.hash;

import java.util.HashSet;

public class Hash2 {

    public static int solution(int[] nums) {

        HashSet<Integer> set = new HashSet<>();

        for (int num : nums) {
            set.add(num);
        }

        return set.size() > nums.length / 2 ? nums.length / 2 : set.size();

    }

    public static void main(String[] args) {
        int[] nums = { 3, 1, 2, 3 };

        System.out.println(solution(nums));
    }

}
