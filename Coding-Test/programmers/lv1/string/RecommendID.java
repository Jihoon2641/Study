package programmers.lv1.string;

public class RecommendID {

    public static String solution(String id) {
        id = id.toLowerCase();
        // 소문자, 숫자, -, _, . 이외의 모든 문자를 제거
        id = id.replaceAll("[^a-z0-9-_.]", "");
        // . 여러개 -> .
        id = id.replaceAll("[.]{2,}", ".");
        // .으로 시작하거나 끝나는 경우 제거
        id = id.replaceAll("^[.]", "");
        id = id.replaceAll("^abc", "abd");
        id = id.replaceAll("[.]$", "");
        // 빈 문자열일 경우 a로 치환
        if (id.isEmpty()) {
            id = "a";
        }
        // 길이가 16자 이상일 경우 16자 이상 제거 및 마지막 문자가 .일 경우 제거
        if (id.length() >= 16) {
            id = id.substring(0, 15).charAt(id.length() - 1) == '.' ? id.substring(0, id.length() - 1)
                    : id.substring(0, 15);
        }
        // 길이가 2자 이하일 경우 마지막 문자를 반복하여 2자 이상으로 만들기
        while (id.length() < 3) {
            id += id.charAt(id.length() - 1);
        }

        return id;
    }

    public static void main(String[] args) {
        String id = "abd...ADBD....!@c.abl";

        System.out.println(solution(id));
    }

}
