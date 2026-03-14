def solution(cpr: list[str]):
    answer = []
    basic_order = ["check", "call", "pressure", "respiration", "repeat"]
    for action in cpr:
        for i in range(cpr.__len__()):
            if action == basic_order[i]:
                answer.append(i + 1)
    return answer

cpr = ["check", "call", "pressure", "respiration", "repeat"]
print(solution(cpr))