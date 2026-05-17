# Model Evaluation Snapshot

Updated: 2026-05-17

이 문서는 기존 모델 실패 분석 산출물을 심사/보고서에서 바로 참조할 수 있도록 축약한 evidence입니다.
재학습 전/후 비교표는 아직 별도 산출물이 필요하며, 이 문서는 현재 모델 기준의 baseline snapshot입니다.

## Sources

| Source | Role |
| --- | --- |
| `docs/Backend model/failure_analysis_20260506_171404.json` | 1007개 평가 결과 원본 스냅샷 |
| `docs/presentation/materials/backend-model-failure-summary-20260506.md` | 발표/보고서용 실패 분석 요약 |
| `evaluation/api-vs-ml/cases.jsonl` | public `/analyze_batch` 회귀 케이스 62개 |

## Classification Summary

| Metric | Value |
| --- | ---: |
| Total cases | 1007 |
| KOLD cases | 760 |
| Handcrafted cases | 247 |
| Correct | 791 |
| Accuracy | 78.55% |
| Precision | 81.51% |
| Recall | 74.46% |
| F1 | 77.82% |
| True Positive | 379 |
| True Negative | 412 |
| False Positive | 86 |
| False Negative | 130 |

## Failure Concentration

| Bucket | FP | FN | Interpretation |
| --- | ---: | ---: | --- |
| `kold_clean` | 72 | 0 | 정상 문맥을 hate/toxicity 신호로 과하게 보는 topic bias가 가장 큼 |
| `kold_offensive_other` | 0 | 55 | 공격성은 있으나 점수가 낮게 나온 주요 미탐 축 |
| `kold_racial_hate` | 0 | 21 | 인종/국적 혐오 미탐 |
| `kold_gender_hate` | 0 | 17 | 성별 혐오 미탐 |
| `kold_religion_hate` | 0 | 6 | 종교 혐오 미탐 |
| `fp_similar_word` | 3 | 0 | 유사 단어 오탐 |
| 기타 | 11 | 31 | quoted/context/emoji/romanized/mixed-language/ultra-short 긴 꼬리 실패 |

## Score Bucket Errors

| Score range | FP | FN | Meaning |
| --- | ---: | ---: | --- |
| `>=0.70` | 66 | 1 | 높은 점수 오탐이 많아 threshold 상승만으로는 해결이 제한적 |
| `0.50-0.70` | 6 | 43 | 중간 점수 미탐이 많아 context/span 보강 필요 |
| `0.30-0.50` | 14 | 85 | 낮은 점수 미탐이 집중되어 우회/간접 표현 보강 필요 |
| `<0.15` | 0 | 1 | 극저점 미탐 소수 |

## Clean Topic Bias Check

`차별금지법` 또는 `성소수자`가 포함된 스냅샷 사례는 32개였습니다.

| Slice | Count |
| --- | ---: |
| Total topic cases | 32 |
| Clean topic cases | 21 |
| Clean topic false positives | 20 |
| Clean topic true negatives | 1 |
| Offensive topic cases correctly detected | 11 |

해석: 현재 스냅샷에서는 민감 토픽 자체가 hate/toxicity 신호로 과하게 작동하는 경향이 강합니다.
따라서 최종 demo 기준은 단순 label boolean이 아니라 `safe-context`, score, evidence span, 플랫폼 위치 근거를 함께 봐야 합니다.

## Representative False Positives

| ID | Category | Max score | Scores | Text excerpt | Evidence span |
| --- | --- | ---: | --- | --- | --- |
| `kold_kold-v1_37001` | `kold_clean` | 0.9189 | p=0.3794 t=0.9114 h=0.9189 | 인권위에서 도대체 누구에게 차별금지법을 얘기했는지 모르겠네요... | - |
| `kold_kold-v1_00344` | `kold_clean` | 0.9378 | p=0.9378 t=0.9122 h=0.174 | 풍년이구나~ | - |
| `kold_kold-v1_22816` | `kold_clean` | 0.9247 | p=0.3767 t=0.9153 h=0.9247 | 차별금지법 찬성 | - |
| `hc_0088` | `fp_slang_positive` | 0.3891 | p=0.3891 t=0.2181 h=0.1598 | 존나 잘생겼다 이 배우 | 존나 |
| `hc_0203` | `quoted_report` | 0.9353 | p=0.9353 t=0.9053 h=0.1616 | 그 사람이 나한테 병신이라고 했어 | 병신 |
| `kold_kold-v1_26684` | `kold_clean` | 0.9226 | p=0.378 t=0.9141 h=0.9226 | 차별금지법을 만들게 아니라 우리나라 모든 대학 이름을 sky로... | - |
| `kold_kold-v1_08471` | `kold_clean` | 0.9244 | p=0.3766 t=0.9152 h=0.9244 | 포괄적 차별금지법 반대합니다 | - |
| `kold_kold-v1_40255` | `kold_clean` | 0.3784 | p=0.3784 t=0.3128 h=0.2576 | 근데 천주교가 무슨 나쁜 교회인줄 알겠어 | 무슨 나쁜 교회인줄 알겠 |
| `kold_kold-v1_09142` | `kold_clean` | 0.9204 | p=0.3756 t=0.912 h=0.9204 | 조영길 목사님 감사합니다... 차별금지법 이땅에서... | - |
| `kold_kold-v1_14466` | `kold_clean` | 0.9245 | p=0.3765 t=0.9152 h=0.9245 | 차별금지법 적극 반대!! | - |

## Representative False Negatives

| ID | Category | Max score | Scores | Text excerpt | Evidence span |
| --- | --- | ---: | --- | --- | --- |
| `kold_kold-v1_25097` | `kold_racial_hate` | 0.5739 | p=0.3793 t=0.5739 h=0.5451 | 탈레반 키운 대표적인 나라에서 하나가 빠졌네요 "미국" | 탈레반 키운 대표적인 나라에서... |
| `kold_kold-v1_02182` | `kold_offensive_other` | 0.3778 | p=0.3778 t=0.2131 h=0.1607 | 성일종, OK! ... 잘 해 봐라!... | - |
| `hc_0223` | `class_discrimination` | 0.382 | p=0.382 t=0.3465 h=0.289 | 노숙자들이 거리 분위기 망치네 | - |
| `kold_kold-v1_21693` | `kold_gender_hate` | 0.5619 | p=0.4034 t=0.5619 h=0.4791 | 성평등은 우리나라발전의 기초가 될거임... | 남자가 억울하다고 여론조장하고... |
| `hc_0224` | `education_discrimination` | 0.3779 | p=0.3779 t=0.2512 h=0.1972 | 명문대 출신들만 뽑으면 되는데 | - |
| `kold_kold-v1_15304` | `kold_gender_hate` | 0.3775 | p=0.3775 t=0.2153 h=0.1618 | 저런걸 낳고 미역국을... | - |
| `kold_kold-v1_24769` | `kold_racial_hate` | 0.5142 | p=0.5142 t=0.4504 h=0.2426 | 개판이다 엿된다 들어 오는순간 코로나는... | 개판 |
| `hc_0009` | `chosung_profanity` | 0.3775 | p=0.3775 t=0.2131 h=0.1608 | ㅎㄷ 대박 짜증 | - |
| `kold_kold-v1_32763` | `kold_offensive_other` | 0.3778 | p=0.3778 t=0.2152 h=0.1615 | 역시 JTBC답게 내용의 본질보다는 학생탓... | - |
| `kold_kold-v1_35247` | `kold_offensive_other` | 0.3787 | p=0.3787 t=0.2138 h=0.1603 | 뭔 의료진? 잉 양반 답답하네... | - |

## Current Limitations

| Requirement | Status |
| --- | --- |
| 재학습 전/후 동일 split 비교 | Missing. 현재 문서는 current/baseline snapshot만 고정함 |
| profanity/toxicity/hate별 독립 metric | Partial. prediction score는 있으나 expected label이 단일 `expected_offensive`라 유형별 precision/recall은 별도 라벨 보강 필요 |
| span detection metric | Partial. 대표 span은 있으나 span-level precision/recall은 별도 채점 set 필요 |
| 최종 demo threshold | Missing. sensitivity별 JSON 결과와 실제 UI masking 기준을 함께 decision으로 고정해야 함 |

